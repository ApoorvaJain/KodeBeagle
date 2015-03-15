/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.betterdocs.spark

import java.io.File

import com.betterdocs.configuration.BetterDocsConfig
import com.betterdocs.crawler.ZipBasicParser
import com.betterdocs.indexer.JavaFileIndexer
import com.betterdocs.logging.Logger
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.spark.{SparkConf, SparkContext}

import scala.collection.mutable.ArrayBuffer
import scala.util.{Failure, Success, Try}

object CreateIndexJob {

  def main(args: Array[String]): Unit = {
    val conf = new SparkConf()
      .setMaster(BetterDocsConfig.sparkMaster)
      .setAppName("CreateIndexJob")
    val sc = new SparkContext(conf)
    sc.binaryFiles(BetterDocsConfig.githubDir).map { case (zipFile, _) =>
      val zipFileName = zipFile.stripPrefix("file:")
      // Ignoring exclude packages.
      val (filesMap, packages) = ZipBasicParser.readFilesAndPackages(new ZipFile(zipFileName))
      (filesMap, RepoFileNameParser(zipFileName), packages)
    }.flatMap { f =>
      val (files, repo, packages) = f
      new JavaFileIndexer()
        .generateTokens(files.toMap, packages, repo)
    }.map(x => toJson(x, addESHeader = true)).saveAsTextFile(BetterDocsConfig.sparkOutput)

    sc.binaryFiles(BetterDocsConfig.githubDir).map { case (zipFile, _) =>
      val zipFileName = zipFile.stripPrefix("file:")
      // Ignoring exclude packages.
      RepoFileNameParser(zipFileName)
    }.flatMap { f => f }.map(x => toJson(x, addESHeader = true, isToken = false))
      .saveAsTextFile(BetterDocsConfig.sparkOutput + "/repo")
  }

  /**
   * This currently uses star counts for a repo as a score.
   */
  def getGitScore(f: String): Option[Int] = {
    Try(f.stripSuffix(".zip").split("~").last.toInt).toOption
  }

  def getOrgsName(f: String): Option[String] = {
    Try(f.stripSuffix(".zip").split("~").tail.head).toOption
  }

  def toJson[T <: AnyRef <% Product with Serializable](t: T, addESHeader: Boolean = false,
      isToken: Boolean = true): String = {
    import org.json4s._
    import org.json4s.jackson.Serialization
    import org.json4s.jackson.Serialization.write
    implicit val formats = Serialization.formats(NoTypeHints)

    if (addESHeader && isToken) {
      """|{ "index" : { "_index" : "betterdocs", "_type" : "custom" } }
         | """.stripMargin + write(t)
    } else if (addESHeader) {
      s"""|{ "index" : { "_index" : "${t.productPrefix.toLowerCase}", "_type" : "type1" } }
          |""".stripMargin + write(t)
    } else "" + write(t)

  }

}

object CreateIndex extends Logger {

  def main(args: Array[String]): Unit = {
    import com.betterdocs.crawler.ZipBasicParser._
    for (f <- listAllFiles(BetterDocsConfig.githubDir)) {
      val zipFile = Try(new ZipFile(f))
      zipFile match {
        case Success(zf) =>
          val tokens: String = getTokens(f, zf)
          scala.tools.nsc.io.File(BetterDocsConfig.sparkOutput + s"/$f.tokens").writeAll(tokens)
        case Failure(e) => log.warn(s"$f failed because ${e.getMessage}")
      }
    }
  }

  def getTokens(f: File, zf: ZipFile): String = {
    val indexer: JavaFileIndexer = new JavaFileIndexer
    import com.betterdocs.crawler.ZipBasicParser._
    import indexer._
    val files: (ArrayBuffer[(String, String)], List[String]) = readFilesAndPackages(zf)
    val repo = RepoFileNameParser(f.getName)
    val tokens = generateTokens(files._1.toMap, files._2, repo)
      .map(CreateIndexJob.toJson(_, addESHeader = true)).mkString("\n")
    tokens
  }
}

object CreateIndexPar extends Logger {

  def main(args: Array[String]): Unit = {
    import com.betterdocs.crawler.ZipBasicParser._
    listAllFiles(BetterDocsConfig.githubDir).par.foreach { f =>
      val zipFile = Try(new ZipFile(f))
      zipFile match {
        case Success(zf) =>
          val tokens: String = CreateIndex.getTokens(f, zf)
          scala.tools.nsc.io.File(BetterDocsConfig.sparkOutput + s"/$f.tokens").writeAll(tokens)
        case Failure(e) => log.warn(s"$f failed because ${e.getMessage}")
      }
    }
  }
}
