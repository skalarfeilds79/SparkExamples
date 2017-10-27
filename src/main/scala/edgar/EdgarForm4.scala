package edgar

import org.apache.spark._
import org.apache.spark.sql._
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.Encoders
import org.apache.log4j.Logger
import scala.util._
import scala.xml._

/**
 * Transformer to parse form4 filing
 */
class Form4FileParser extends EdgarFilingProcessor[(String, String)]  {
  
  override def edgarEncoder = org.apache.spark.sql.Encoders.kryo[(String, String)]
  override def parseXML = content => parseForm4(content)
  
  def parseForm4(fileContent: String): (String, String) = {
    if (fileContent.length() > 0) {
      val content = fileContent.substring(fileContent.indexOf("?>") + 2, fileContent.indexOf("</XML"))
      val xml = XML.loadString(content)
      val formType = xml \\ "submissionType"
      val issuerName = xml \\ "issuerName"
      val issuerCik = xml \\ "issuerCik"
      val reportingOwnerCik = xml \\ "rptOwnerCik"
      val transactionCode = xml \\ "transactionCode"
      (issuerCik.text, transactionCode.text)

    } else {
      ("Unknown", "-1")
    }
  }
}

class Form4Aggregator extends Transformer[Dataset[(String, String)], Dataset[(String, Long)]] {
  // aggregates all the data returned.
  // basically, all the transaction codes returned by every form4
  @transient
  val logger: Logger = Logger.getLogger("EdgarFilingReader.Form4Aggregator")
  
  
  override def transform(sparkContext:SparkContext, inputDataSet:Dataset[(String, String)]):Dataset[(String, Long)] = {
    import org.apache.spark.sql.Encoders
    val sqlContext = new SQLContext(sparkContext)
    import sqlContext.implicits._
    val flatMapped = inputDataSet.flatMap { tpl => tpl._2.map(_.toString) }
    val res = flatMapped.groupByKey(identity).count
    res 
  }
  
}

class Form4Processor extends Transformer[Dataset[String], Dataset[(String, Long)]] { 
  private val form4FileParser = new Form4FileParser();
  private val form4Aggregator = new Form4Aggregator();
  
  private[edgar] def parseFunction(implicit sparkContext:SparkContext) =
      (inputDataSet:Dataset[String]) => form4FileParser.transform(sparkContext, inputDataSet)
  
  private[edgar] def aggregateFunction(implicit sparkContext:SparkContext) = 
      (inputDataSet:Dataset[(String, String)]) => form4Aggregator.transform(sparkContext, inputDataSet)
      
  override def transform(sc: SparkContext, inputDataSet: Dataset[String]):Dataset[(String, Long)] = {
    implicit val sparkContext = sc
    val composed = parseFunction andThen aggregateFunction
    composed(inputDataSet)
  }
    
  
}

