
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
import org.apache.log4j.{Level, Logger}    
 


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
object DecisionTreeExampleMLHotEncoding {
  import org.apache.spark.SparkConf
  import org.apache.spark.SparkContext

  val logger: Logger = Logger.getLogger("DecisionTree.Sample.MLHotEncoding")
  
  def getDataFrame(sc: SparkContext, args: Array[String]): DataFrame = {

    val filename = args.size match {
      case 1 => args(0)
      case 2 => "file:///c:/Users/marco/SparkExamples/src/main/resources/covtype.data.gz"
    }

    logger.info(s"Loading data from $filename")

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

    val data = dataWithoutHeader.toDF(colNames: _*).withColumn("Cover_Type", col("Cover_Type").cast("double"))

    data
  }

  def unencodeOneHot(data: DataFrame): DataFrame = {
    val wildernessCols = (0 until 4).map(i => s"Wilderness_Area_$i").toArray

    val wildernessAssembler = new VectorAssembler().
      setInputCols(wildernessCols).
      setOutputCol("wilderness")

    val unhotUDF = udf((vec: Vector) => vec.toArray.indexOf(1.0).toDouble)

    val withWilderness = wildernessAssembler.transform(data)

    var droppedDf = withWilderness
    logger.info("Before we have:" + droppedDf.columns.size)
    for (col <- wildernessCols)
      droppedDf = droppedDf.drop(col)
    logger.info("After we haveL" + droppedDf.columns.size)
    logger.info(droppedDf.columns.mkString(","))

    val withAddedWilderness = droppedDf.withColumn("wilderness", unhotUDF(col("wilderness")))

    val soilCols = (0 until 40).map(i => s"Soil_Type_$i").toArray
    val soilAssembler = new VectorAssembler().
      setInputCols(soilCols).
      setOutputCol("soil")

    val transformedWilderness = soilAssembler.transform(withAddedWilderness)
    var noSoilDf = transformedWilderness

    logger.info("Before we have:" + noSoilDf.columns.size)
    for (soilCol <- soilCols)
      noSoilDf = noSoilDf.drop(soilCol)

    logger.info("After we have:" + noSoilDf.columns.size)

    val withOneHotSoil = noSoilDf.withColumn("soil", unhotUDF(col("soil")))

    withOneHotSoil
  }

  def evaluate(trainData: DataFrame, testData: DataFrame): Unit = {

    
    // NEEDED FOR PIPELINE
    val assembler = new VectorAssembler().
      setInputCols(trainData.columns.filter(_ != "Cover_Type")).
      setOutputCol("featureVector")

    // NEEDED FOR PIPELINE  
    val classifier = new DecisionTreeClassifier().
      setSeed(Random.nextLong()).
      setLabelCol("Cover_Type").
      setFeaturesCol("featureVector").
      setPredictionCol("prediction")

    val pipeline = new Pipeline().setStages(Array(assembler, classifier))

    // DONE. NOW ALL THE STUFF BELOW IS NEEDED
    // TO FIND  THE BEST MODEL
    
    // WE NEED TO CREATE A PARAM GRID USING CLASSIFIER AND
    // FEW PARAMETERS
    val paramGrid = new ParamGridBuilder().
      addGrid(classifier.impurity, Seq("gini", "entropy")).
      addGrid(classifier.maxDepth, Seq(1, 20)).
      addGrid(classifier.maxBins, Seq(40, 300)).
      addGrid(classifier.minInfoGain, Seq(0.0, 0.05)).
      build()

    // THEN WE NEED A MULTICLASSIFICATION EVALUATOR WITH LABELCOL,
    // PREDICT COL AND METRIC AME
    val multiclassEval = new MulticlassClassificationEvaluator().
      setLabelCol("Cover_Type").
      setPredictionCol("prediction").
      setMetricName("accuracy")

    // THEN WE NEED A VALIDATOR USING PIPELINE, MULTICLASS EVAL AND
    // PARAMGRID
      
    // SO PERHAPS OUR BEST MODEL UTILITY WILL JUST ACCEPT AS INPUT PARAMETER
    // 4 ITEMS:
    // 1.PIPELINE
    // 2.MULTICLASS EVAL
    // 3. PARAMGRID
    // 4. TRAINDATA.
    // AS EVERTHING BEFORE THIS IS CUSTOM TO THE SPECIFIC MODEL  
     
    val validator = new TrainValidationSplit().
      setSeed(Random.nextLong()).
      setEstimator(pipeline).
      setEvaluator(multiclassEval).
      setEstimatorParamMaps(paramGrid).
      setTrainRatio(0.9)

    //spark.sparkContext.setLogLevel("DEBUG")
    val validatorModel = validator.fit(trainData)
    /*
    DEBUG TrainValidationSplit: Got metric 0.6315930234779452 for model trained with {
      dtc_ca0f064d06dd-impurity: gini,
      dtc_ca0f064d06dd-maxBins: 10,
      dtc_ca0f064d06dd-maxDepth: 1,
      dtc_ca0f064d06dd-minInfoGain: 0.0
    }.
    */
    //spark.sparkContext.setLogLevel("WARN")

    val bestModel = validatorModel.bestModel

    logger.info("============== BEST MODEL ")
    logger.info(bestModel.asInstanceOf[PipelineModel].stages.last.extractParamMap)

    logger.info("============== VALIDATION METRICS ")
    logger.info(validatorModel.validationMetrics.max)

    val testAccuracy = multiclassEval.evaluate(bestModel.transform(testData))
    logger.info("============== TEST ACCURACY MODEL ")
    logger.info(testAccuracy)
    val trainAccuracy = multiclassEval.evaluate(bestModel.transform(trainData))
    logger.info("============== TRAIN ACCURACY MODEL ")

    logger.info(trainAccuracy)
  }

  def generateDecisionTree(sconf: SparkConf, args: Array[String]): Unit = {

    SparkUtil.disableSparkLogging
    logger.info(s"Attempting to load:${args(0)}")

    val sc = new SparkContext("local[*]", "DecisionTreeExampleML")
    val df = getDataFrame(sc, args)

    logger.info("InputData:" + df.count())
    val reduced = args.size match {
      case 1 => df
      case 2 => df.sample(false, 0.001)
    }

    val Array(trainData, testData) = reduced.randomSplit(Array(0.9, 0.1))
    trainData.cache()
    testData.cache()

    evaluate(trainData, testData)

  }

  def main(args: Array[String]) = {
    val conf = new SparkConf().setAppName("Simple Application")
    generateDecisionTree(conf, args)
  }

}