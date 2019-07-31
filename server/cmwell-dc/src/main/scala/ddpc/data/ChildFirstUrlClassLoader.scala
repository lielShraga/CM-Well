/**
  * Copyright 2015 Thomson Reuters
  *
  * Licensed under the Apache License, Version 2.0 (the “License”); you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  *   http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
  * an “AS IS” BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  *
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */
package ddpc.data

import java.net.{URL, URLClassLoader}

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Framing, Source}
import akka.util.ByteString
import ChildFirstURLClassLoader.Loader

import scala.concurrent.ExecutionContext

object ChildFirstURLClassLoader {
  def loadClassFromJar[T](className: String, jarPath: String, excludes: Seq[String] = Seq()): T =
    Loader(jarPath, excludes).load(className)

  case class Loader(jarPath: String, excludes: Seq[String] = Seq()) {
    val classLoaderUrls: Array[URL] = Array[URL](new URL(jarPath))
    private val cl = new URLClassLoader(classLoaderUrls, this.getClass.getClassLoader)
    //      new ChildFirstURLClassLoader(Array(new File(jarPath).toURI.toURL), this.getClass.getClassLoader, excludes)
    def load[T](className: String) = cl.loadClass(className).newInstance.asInstanceOf[T]
  }
}
