/*
 *  Copyright 2016 by Ji Sun
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

// scalastyle:off println

package org.apache.spark.sql.index

/**
  * Created by sunji on 16/11/17.
  */

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.sql.SimilarityProbe.ValueInfo
import scala.collection.mutable


case class EdExtra(frequencyTable: Broadcast[scala.collection.Map[(Int, Boolean), Long]],
                 minimum: Int, partitionNum: Int)

class EdIndex() extends Index with Serializable {
  val index = scala.collection.mutable.Map[Int, List[Int]]()
  var threshold = 0
  var extra: EdExtra = null

  private def compareSimilarity(query: ValueInfo, index: ValueInfo, threshold: Int): Boolean = {
    val queryHash = query.record.hashCode
    val indexHash = index.record.hashCode

    if (!(query.isDeletion ^ index.isDeletion)) {
      EDdistance(query.record, index.record) <= threshold
    } else {
      false
    }
  }

  private def EDdistance(s1: String, s2: String): Int = {
    val dist = Array.tabulate(s2.length + 1, s1.length + 1) {
      (j, i) => if (j == 0) i else if (i == 0) j else 0
    }

    for (j <- 1 to s2.length; i <- 1 to s1.length)
      dist(j)(i) = if (s2(j - 1) == s1(i - 1)) dist(j - 1)(i - 1)
      else math.min(math.min(dist(j - 1)(i) + 1, dist(j)(i - 1) + 1), dist(j - 1)(i - 1) + 1)

    dist(s2.length)(s1.length)
  }

  def init(threshold: Int,
           frequencyT: Broadcast[scala.collection.Map[(Int, Boolean), Long]],
           minimum: Int,
           partitionNum: Int): Unit = {
    this.threshold = threshold
    this.extra = EdExtra(frequencyT, minimum, partitionNum)
  }

  def addIndex(key: Int, position: Int): Unit = {
    index += (key -> (position :: index.getOrElse(key, List())))
  }

  def sampleSelectivity(data: Array[ValueInfo],
                        key: Array[(Int, ValueInfo)],
                        t: Int, sampleRate: Double): Double = {
    val sampledKey: Seq[(Int, ValueInfo)] = {
      for (i <- 1 to math.max(1, (sampleRate * key.length).toInt);
           r = (Math.random * key.size).toInt) yield key(r)
    }
    var selectivity = (0, 0.0)
    for (query <- sampledKey) {
      val positionSize = index.getOrElse(query._1, List()).length.toDouble
      selectivity = (selectivity._1 + 1, selectivity._2 + positionSize / data.size)
    }

    selectivity._2 / math.max(1.0, selectivity._1)
  }

  def findIndex(data: Array[ValueInfo],
                key: Array[(Int, ValueInfo)],
                t: Int): Array[InternalRow] = {

    val ans = mutable.ListBuffer[InternalRow]()

    for (query <- key) {
      val position = index.getOrElse(query._1, List())
      for (p <- position) {
        //          println(s"Found in Index")
        if (compareSimilarity(query._2, data(p), t)) {
          ans += data(p).content
        }
      }
    }
    ans.toArray
  }

  def sequentialScan(data: Array[ValueInfo],
                key: Array[(Int, ValueInfo)],
                t: Int): Array[InternalRow] = {

    val ans = mutable.ListBuffer[InternalRow]()

    for (query <- key) {
      for (entry <- data) {
        if (compareSimilarity(query._2, entry, t)) {
          ans += entry.content
        }
      }
    }
    ans.toArray
  }
}

object EdIndex {
  def apply(data: Array[(Int, ValueInfo)],
            threshold: Int,
            frequencyT: Broadcast[scala.collection.Map[(Int, Boolean), Long]],
            minimum: Int,
            partitionNum: Int): EdIndex = {
    val res = new EdIndex()
    res.init(threshold, frequencyT, minimum, partitionNum)
    for (i <- 0 until data.length) {
      res.addIndex(data(i)._1, i)
    }
    res
  }
}