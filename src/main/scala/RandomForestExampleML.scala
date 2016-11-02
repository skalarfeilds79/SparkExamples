
import org.apache.spark.mllib.linalg.{ Vector, Vectors }
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.ml.classification.{ RandomForestClassifier, RandomForestClassificationModel }
import org.apache.spark.SparkContext
import org.apache.spark.ml.feature.{ StringIndexer, IndexToString, VectorIndexer, VectorAssembler }
import org.apache.spark.ml.evaluation.{ RegressionEvaluator, MulticlassClassificationEvaluator }
import org.apache.spark.ml.classification._
import org.apache.spark.ml.tuning.{ CrossValidator, ParamGridBuilder }
import org.apache.spark.ml.tuning.{ ParamGridBuilder, TrainValidationSplit }
import org.apache.spark.rdd.RDD
import org.apache.spark.sql._
import org.apache.spark.sql.SQLImplicits
import org.apache.spark.sql.Row
import org.apache.spark.sql.types._
import org.apache.spark.ml.{ Pipeline, PipelineModel }
import org.apache.spark.sql.functions._
import scala.util.Random

/**
 * This example builds a random forest tree and it's based on this video
 * on youtube
 * It has been rewritten to use spark ml instead of mllib
 *
 * https://www.youtube.com/watch?v=ObiCMJ24ezs
 *
 * Please note that this code depends on pacakge spark-csv, so make sure when you
 * launch spark-submit you provide --packages com.databricks:spark-csv_2.10:1.4.0
 *
 *
 * copy relevant code here
 *
 * A  good example for this is at this link
 *
 * https://github.com/sryza/aas/blob/master/ch04-rdf/src/main/scala/com/cloudera/datascience/rdf/RunRDF.scala#L220
 *
 *
 *
 *
 *
 * Run it like this
 * C:\Users\marco\SparkExamples>spark-submit
 * --class RamdomForestExampleML
 * target\scala-2.11\sparkexamples.jar
 * <path to tree_addhealth.csv>
 *
 *
 *
 */
object RandomForestExampleML {
  import org.apache.spark.SparkConf
  import org.apache.spark.SparkContext

  def getDataFrame(sc: SparkContext, args: Array[String]): DataFrame = {

    val filename = args.size match {
      case 1 => args(0)
      case 2 => "file://c:/Users/marco/SparkExamples/src/main/resources/covtype.data.gz"
    }

    println(s"Loading data from $filename")

    val sqlContext = new SQLContext(sc)

    val dataWithoutHeader = sqlContext.read
      .format("com.databricks.spark.csv")
      .option("inferSchema", "true")
      .option("header", "false")
      .load(filename)

    val colNames = Seq(
      "Elevation", "Aspect", "Slope",
      "Horizontal_Distance_To_Hydrology", "Vertical_Distance_To_Hydrology",
      "Horizontal_Distance_To_Roadways",
      "Hillshade_9am", "Hillshade_Noon", "Hillshade_3pm",
      "Horizontal_Distance_To_Fire_Points") ++ (
        (0 until 4).map(i => s"Wilderness_Area_$i")) ++ (
          (0 until 40).map(i => s"Soil_Type_$i")) ++ Seq("Cover_Type")

    val data = dataWithoutHeader.toDF(colNames: _*)

    val withCoverTypeDoubled = data.withColumn("Cover_TypeDbl", col("Cover_Type").cast("double")).drop("Cover_Type")
      .withColumnRenamed("Cover_TypeDbl", "Cover_Type")

    withCoverTypeDoubled
  }

  def unencodeOneHot(data: DataFrame): DataFrame = {
    val wildernessCols = (0 until 4).map(i => s"Wilderness_Area_$i").toArray
    
    val wildernessAssembler = new VectorAssembler().
      setInputCols(wildernessCols).
      setOutputCol("wilderness")

    val unhotUDF = udf((vec: Vector) => vec.toArray.indexOf(1.0).toDouble)

    val withWilderness = wildernessAssembler.transform(data)

    var droppedDf = withWilderness
    println("Before we have:" + droppedDf.columns.size)
    for (col <- wildernessCols)
      droppedDf = droppedDf.drop(col)
    println("After we haveL" + droppedDf.columns.size)
    println(droppedDf.columns.mkString(","))

    val withAddedWilderness = droppedDf.withColumn("wilderness", unhotUDF(col("wilderness")))

    val soilCols = (0 until 40).map(i => s"Soil_Type_$i").toArray
    val soilAssembler = new VectorAssembler().
      setInputCols(soilCols).
      setOutputCol("soil")

    val transformedWilderness = soilAssembler.transform(withAddedWilderness)
    var noSoilDf = transformedWilderness

    println("Before we have:" + noSoilDf.columns.size)
    for (soilCol <- soilCols)
      noSoilDf = noSoilDf.drop(soilCol)

    println("After we have:" + noSoilDf.columns.size)

    val withOneHotSoil = noSoilDf.withColumn("soil", unhotUDF(col("soil")))

    withOneHotSoil
  }

  def findBestModel(classifier:RandomForestClassifier, 
                    unencTrainData:DataFrame , 
                    unencTestData:DataFrame,
                    pipeline:Pipeline) = {
    val paramGrid = new ParamGridBuilder().
      addGrid(classifier.minInfoGain, Seq(0.0, 0.05)).
      addGrid(classifier.numTrees, Seq(1, 10)).
      build()

    val multiclassEval = new MulticlassClassificationEvaluator().
      //setLabelCol("Cover_Type").
      setLabelCol("indexedLabel").
      setPredictionCol("prediction").
      setMetricName("precision")

    val validator = new TrainValidationSplit().
      setEstimator(pipeline).
      setEvaluator(multiclassEval).
      setEstimatorParamMaps(paramGrid).
      setTrainRatio(0.9)

    val validatorModel = validator.fit(unencTrainData)

    
    val bestModel = validatorModel.bestModel

    println("====== And The Best Model is:" + bestModel)
    
    println("===== carry on ====")
    /**
    val forestModel = bestModel.asInstanceOf[PipelineModel].
      stages.last.asInstanceOf[RandomForestClassificationModel]

    
    println("########################PARAMETERS ARE:\n" + forestModel.extractParamMap)
    println("########################NUMTREEES ARE:" + forestModel.numTrees)
    forestModel.featureImportances.toArray.zip(unencTrainData.columns).
      sorted.reverse.foreach(println)

    println("========== TESTING ACCURACY ===========")
    val testAccuracy = multiclassEval.evaluate(bestModel.transform(unencTestData))
    println(testAccuracy)

    bestModel.transform(unencTestData.drop("Cover_Type")).select("prediction").show()
		**/
  }
  
  
  def evaluateForest(data: DataFrame): Unit = {

    val Array(trainData, testData) = data.randomSplit(Array(0.9, 0.1))
    trainData.cache()
    testData.cache()

    val unencTrainData = unencodeOneHot(trainData)
    val unencTestData = unencodeOneHot(testData)
    unencTrainData.cache()
    unencTestData.cache()

    val labelIndexer = new StringIndexer()
      .setInputCol("Cover_Type")
      .setOutputCol("indexedLabel")
      .setHandleInvalid("skip")
      .fit(data)
      
    val assembler = new VectorAssembler().
      setInputCols(unencTrainData.columns.filter(_ != "Cover_Type")).
      setOutputCol("featureVector")
      

    val indexer = new VectorIndexer().
      setMaxCategories(40).
      setInputCol("featureVector").
      setOutputCol("indexedVector")
      

    val classifier = new RandomForestClassifier().
      setSeed(Random.nextLong()).
      setLabelCol("indexedLabel"). //"Cover_Type").
      setFeaturesCol("indexedVector").
      setPredictionCol("prediction").
      setImpurity("entropy").
      setMaxDepth(20).
      setMaxBins(300)

    val pipeline = new Pipeline().setStages(Array(labelIndexer, assembler, indexer, classifier))// labelConverter))

    // Train model.  This also runs the indexers.
    val model = pipeline.fit(unencTrainData)
    
    // Make predictions.
    val predictions = model.transform(unencTestData)
    // Select example rows to display.
    println("------ displaying predictions -----------------")
    predictions.select("prediction", "indexedLabel", "indexedVector").show(5)
   
    println("================== NOW CALCULATING BEST MODEL.. ================================")

  }

  def generateDecisionTree(sconf: SparkConf, args: Array[String]): Unit = {

    SparkUtil.disableSparkLogging
    println(s"Attempting to load:${args(0)}")

    val sc = new SparkContext("local[*]", "RandomForestExampleML")
    val df = getDataFrame(sc, args)

    println("InputData:" + df.count())
    val reduced = args.size match {
      case 1 => df
      case 2 => df.sample(false, 0.001)
    }

    println("Unenconding one hot...")
    evaluateForest(reduced) //trainData, testData)
    
  }

  def main(args: Array[String]) = {
    val conf = new SparkConf().setAppName("Simple Application")
    generateDecisionTree(conf, args)
  }

}