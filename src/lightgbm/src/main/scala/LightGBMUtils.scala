// Copyright (C) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License. See LICENSE in project root for information.

package com.microsoft.ml.spark

import com.microsoft.ml.lightgbm._
import org.apache.spark.{BlockManagerUtils, SparkEnv, TaskContext}
import org.apache.spark.ml.PipelineModel
import org.apache.spark.ml.linalg.SparseVector
import org.apache.spark.sql.{DataFrame, Dataset, Row}

/**
  * Helper utilities for LightGBM learners
  */
object LightGBMUtils {
  def validate(result: Int, component: String): Unit = {
    if (result == -1) {
      throw new Exception(component + " call failed in LightGBM with error: " + lightgbmlib.LGBM_GetLastError())
    }
  }

  /** Loads the native shared object binaries lib_lightgbm.so and lib_lightgbm_swig.so
    */
  def initializeNativeLibrary(): Unit = {
    new NativeLoader("/com/microsoft/ml/lightgbm").loadLibraryByName("_lightgbm")
    new NativeLoader("/com/microsoft/ml/lightgbm").loadLibraryByName("_lightgbm_swig")
  }

  def featurizeData(dataset: Dataset[_], labelColumn: String, featuresColumn: String): PipelineModel = {
    // Create pipeline model to featurize the dataset
    val oneHotEncodeCategoricals = true
    val featuresToHashTo = FeaturizeUtilities.numFeaturesTreeOrNNBased
    val featureColumns = dataset.columns.filter(col => col != labelColumn).toSeq
    val featurizer = new Featurize()
      .setFeatureColumns(Map(featuresColumn -> featureColumns))
      .setOneHotEncodeCategoricals(oneHotEncodeCategoricals)
      .setNumberOfFeatures(featuresToHashTo)
    featurizer.fit(dataset)
  }

  /** Returns an integer ID for the current node.
    * @return In cluster, returns the executor id.  In local case, returns the worker id.
    */
  def getId(): Int = {
    val executorId = SparkEnv.get.executorId
    val ctx = TaskContext.get
    val partId = ctx.partitionId
    // If driver, this is only in test scenario, make each partition a separate worker
    val id = if (executorId == "driver") partId else executorId
    val idAsInt = id.toString.toInt
    idAsInt
  }

  /** Returns the executors.
    * @param dataset The dataset containing the current spark session.
    * @param defaultListenPort The default port to listen on.
    * @return List of executors in host:port format.
    */
  def getExecutors(dataset: Dataset[_], defaultListenPort: Int): Array[String] = {
    val blockManager = BlockManagerUtils.getBlockManager(dataset)
    blockManager.master.getMemoryStatus.flatMap({ case (blockManagerId, _) =>
      if (blockManagerId.executorId == "driver") None
      else Some(blockManagerId.host + ":" + (defaultListenPort + blockManagerId.executorId.toInt))
    }).toArray
  }

  /**
    * Returns the number of executors.
    * @param dataset The dataset containing the current spark session.
    * @param defaultListenPort The default port to listen on.
    * @return The number of executors.
    */
  def getNumExecutors(dataset: Dataset[_], defaultListenPort: Int): Int = {
    val executors = getExecutors(dataset, defaultListenPort)
    if (executors.isEmpty) {
      // Case when run locally in local[*]
      val blockManager = BlockManagerUtils.getBlockManager(dataset)
      blockManager.master.getMemoryStatus.size
    } else {
      executors.length
    }
  }

  /** Returns the executor node ips and ports.
    * @param data The input dataframe.
    * @param defaultListenPort The default listen port.
    * @return List of nodes as comma separated string and count.
    */
  def getNodes(data: DataFrame, defaultListenPort: Int): (String, Int) = {
    val nodes = getExecutors(data, defaultListenPort)
    if (nodes.isEmpty) {
      // Running in local[*]
      getNodesFromPartitions(data, defaultListenPort)
    } else {
      // Running on cluster, include all workers with driver excluded
      (nodes.sorted.distinct.reduceLeft((val1, val2) => val1 + "," + val2), nodes.size)
    }
  }

  /** Returns the nodes from mapPartitions.  Only run in local[*] case.
    * @param processedData The input data.
    * @param defaultListenPort The default listening port.
    * @return The list of nodes in host:port format.
    */
  def getNodesFromPartitions(processedData: DataFrame, defaultListenPort: Int): (String, Int) = {
    import processedData.sparkSession.implicits._
    val blockManager = BlockManagerUtils.getBlockManager(processedData)
    val host = blockManager.master.getMemoryStatus.flatMap({ case (blockManagerId, _) =>
      Some(blockManagerId.host)
    }).head
    val nodes = processedData.mapPartitions((iter: Iterator[Row]) => {
      // The logic below is to get it to run in local[*] spark context
      val id = getId()
      Array(host + ":" + (defaultListenPort + id)).toIterator
    }).collect().sorted.distinct.reduceLeft((val1, val2) => val1 + "," + val2)
    (nodes, nodes.split(",").length)
  }

  def newDoubleArray(array: Array[Double]): (SWIGTYPE_p_void, SWIGTYPE_p_double) = {
    val data = lightgbmlib.new_doubleArray(array.length)
    array.zipWithIndex.foreach {
      case (value, index) => lightgbmlib.doubleArray_setitem(data, index, value)
    }
    (lightgbmlib.double_to_voidp_ptr(data), data)
  }

  def newIntArray(array: Array[Int]): (SWIGTYPE_p_int32_t, SWIGTYPE_p_int) = {
    val data = lightgbmlib.new_intArray(array.length)
    array.zipWithIndex.foreach {
      case (value, index) => lightgbmlib.intArray_setitem(data, index, value)
    }
    (lightgbmlib.int_to_int32_t_ptr(data), data)
  }

  def intToPtr(value: Int): SWIGTYPE_p_int64_t = {
    val longPtr = lightgbmlib.new_longp()
    lightgbmlib.longp_assign(longPtr, value)
    lightgbmlib.long_to_int64_t_ptr(longPtr)
  }

  def generateData(numRows: Int, rowsAsDoubleArray: Array[Array[Double]]): (SWIGTYPE_p_void, SWIGTYPE_p_double) = {
    val numCols = rowsAsDoubleArray.head.length
    val data = lightgbmlib.new_doubleArray(numCols * numRows)
    rowsAsDoubleArray.zipWithIndex.foreach(ri =>
      ri._1.zipWithIndex.foreach(value =>
        lightgbmlib.doubleArray_setitem(data, value._2 + (ri._2 * numCols), value._1)))
    (lightgbmlib.double_to_voidp_ptr(data), data)
  }

  def generateDenseDataset(numRows: Int, rowsAsDoubleArray: Array[Array[Double]]): SWIGTYPE_p_void = {
    val numRowsIntPtr = lightgbmlib.new_intp()
    lightgbmlib.intp_assign(numRowsIntPtr, numRows)
    val numRows_int32_tPtr = lightgbmlib.int_to_int32_t_ptr(numRowsIntPtr)
    val numCols = rowsAsDoubleArray.head.length
    val isRowMajor = 1
    val numColsIntPtr = lightgbmlib.new_intp()
    lightgbmlib.intp_assign(numColsIntPtr, numCols)
    val numCols_int32_tPtr = lightgbmlib.int_to_int32_t_ptr(numColsIntPtr)
    val datasetOutPtr = lightgbmlib.voidpp_handle()
    val datasetParams = "max_bin=255 is_pre_partition=True"
    val data64bitType = lightgbmlibConstants.C_API_DTYPE_FLOAT64
    var data: Option[(SWIGTYPE_p_void, SWIGTYPE_p_double)] = None
    try {
      data = Some(generateData(numRows, rowsAsDoubleArray))
      // Generate the dataset for features
      LightGBMUtils.validate(lightgbmlib.LGBM_DatasetCreateFromMat(data.get._1, data64bitType,
        numRows_int32_tPtr, numCols_int32_tPtr, isRowMajor, datasetParams, null, datasetOutPtr), "Dataset create")
    } finally {
      if (data.isDefined) {
        lightgbmlib.delete_doubleArray(data.get._2)
      }
    }
    lightgbmlib.voidpp_value(datasetOutPtr)
  }

  /** Generates a sparse dataset in CSR format.
    * @param sparseRows The rows of sparse vector.
    * @return
    */
  def generateSparseDataset(sparseRows: Array[SparseVector]): SWIGTYPE_p_void = {
    var values: Option[(SWIGTYPE_p_void, SWIGTYPE_p_double)] = None
    var indexes: Option[(SWIGTYPE_p_int32_t, SWIGTYPE_p_int)] = None
    var indptrNative: Option[(SWIGTYPE_p_int32_t, SWIGTYPE_p_int)] = None
    try {
      val valuesArray = sparseRows.flatMap(_.values)
      values = Some(newDoubleArray(valuesArray))
      val indexesArray = sparseRows.flatMap(_.indices)
      indexes = Some(newIntArray(indexesArray))
      val indptr = new Array[Int](sparseRows.length + 1)
      sparseRows.zipWithIndex.foreach {
        case (row, index) => indptr(index + 1) = indptr(index) + row.numNonzeros
      }
      indptrNative = Some(newIntArray(indptr))
      val numCols = sparseRows(0).size

      val datasetOutPtr = lightgbmlib.voidpp_handle()
      val datasetParams = "max_bin=255 is_pre_partition=True"
      val dataInt32bitType = lightgbmlibConstants.C_API_DTYPE_INT32
      val data64bitType = lightgbmlibConstants.C_API_DTYPE_FLOAT64

      // Generate the dataset for features
      LightGBMUtils.validate(CSRUtils.LGBM_DatasetCreateFromCSR(indptrNative.get._1, dataInt32bitType,
        indexes.get._1, values.get._1, data64bitType, intToPtr(indptr.length), intToPtr(valuesArray.length),
        intToPtr(numCols), datasetParams, null, datasetOutPtr), "Dataset create")
      lightgbmlib.voidpp_value(datasetOutPtr)
    } finally {
      // Delete the input rows
      if (values.isDefined) {
        lightgbmlib.delete_doubleArray(values.get._2)
      }
      if (indexes.isDefined) {
        lightgbmlib.delete_intArray(indexes.get._2)
      }
      if (indptrNative.isDefined) {
        lightgbmlib.delete_intArray(indptrNative.get._2)
      }
    }
  }
}
