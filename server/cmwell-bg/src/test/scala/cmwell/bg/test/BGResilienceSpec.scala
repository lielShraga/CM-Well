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
package cmwell.bg.test

import java.util.Properties

import akka.actor.{ActorRef, ActorSystem}
import cmwell.bg.{CMWellBGActor, ShutDown}
import cmwell.common.{CommandSerializer, OffsetsService, WriteCommand, ZStoreOffsetsService}
import cmwell.domain.{FieldValue, Infoton, ObjectInfoton}
import cmwell.driver.Dao
import cmwell.fts._
import cmwell.irw.IRWService
import cmwell.util.{Box, FullBox}
import cmwell.util.testSuitHelpers.test.EsCasKafkaZookeeperDockerSuite
import cmwell.zstore.ZStore
import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}
import com.typesafe.scalalogging.LazyLogging
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest
import org.elasticsearch.common.unit.TimeValue
import org.scalatest.{AsyncFlatSpec, BeforeAndAfterAll, FlatSpec, Matchers}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.io.Source

/**
  * Created by israel on 13/09/2016.
  */
case class CasElasResponse(casandraRes:Future[Box[Infoton]], elasticRes:Future[FTSSearchResponse], index:Int)
class BGResilienceSpec extends AsyncFlatSpec with BeforeAndAfterAll with BgEsCasKafkaZookeeperDockerSuite with Matchers with LazyLogging {

  var kafkaProducer:KafkaProducer[Array[Byte], Array[Byte]] = _
  var cmwellBGActor:ActorRef = _
  var dao:Dao = _
  var testIRWMockupService:IRWService = _
  var irwService:IRWService = _
  var zStore:ZStore = _
  var offsetsService:OffsetsService = _
  var ftsServiceES:FTSService = _
  var bgConfig:Config = _
  var actorSystem:ActorSystem = _

  override def beforeAll = {
    //notify ES to not set Netty's available processors
    System.setProperty("es.set.netty.runtime.available.processors", "false")
    kafkaProducer = BgTestHelpers.kafkaProducer(s"${kafkaContainer.containerIpAddress}:${kafkaContainer.mappedPort(9092)}")
    // scalastyle:on
    dao = BgTestHelpers.dao(cassandraContainer.containerIpAddress, cassandraContainer.mappedPort(9042))
    testIRWMockupService = FailingIRWServiceMockup(dao, 13)
    zStore = ZStore(dao)
    irwService = IRWService.newIRW(dao, 25 , true, 0.seconds)
    offsetsService = new ZStoreOffsetsService(zStore)
    val ftsOverridesConfig = BgTestHelpers.ftsOverridesConfig(elasticsearchContainer.containerIpAddress, elasticsearchContainer.mappedPort(9300))
    ftsServiceES = FailingFTSServiceMockup(ftsOverridesConfig, 5)
    // delete all existing indices - not needed the docker is started fresh every time
    //ftsServiceES.client.admin().indices().delete(new DeleteIndexRequest("_all"))
    BgTestHelpers.initFTSService(ftsServiceES)
    bgConfig = ftsOverridesConfig
      .withValue("cmwell.bg.esActionsBulkSize", ConfigValueFactory.fromAnyRef(100))
      .withValue("cmwell.bg.kafka.bootstrap.servers", ConfigValueFactory.fromAnyRef(s"${kafkaContainer.containerIpAddress}:${kafkaContainer.mappedPort(9092)}"))
    actorSystem = ActorSystem("cmwell-bg-test-system")
    cmwellBGActor = actorSystem.actorOf(CMWellBGActor.props(0, bgConfig, testIRWMockupService, ftsServiceES, zStore, offsetsService))
  }

  "Resilient BG" should "process commands as usual on circumvented BGActor (periodically failing IRWService) after suspending and resuming" in {

    val numOfCommands = 1500
    // prepare sequence of writeCommands
    val writeCommands = Seq.tabulate(numOfCommands){ n =>
      val infoton = ObjectInfoton(
        path = s"/cmt/cm/bg-test/circumvented_bg/info$n",
        dc = "dc",
        indexTime = None,
        fields = Some(Map("games" -> Set(FieldValue("Taki"), FieldValue("Race")))),
        protocol = None)
      WriteCommand(infoton)
    }

    // make kafka records out of the commands
    val pRecords = writeCommands.map{ writeCommand =>
      val commandBytes = CommandSerializer.encode(writeCommand)
      new ProducerRecord[Array[Byte], Array[Byte]]("persist_topic", commandBytes)
    }

    // send them all
    pRecords.foreach { kafkaProducer.send(_)}

    val casElasResultList = for(i <- 0 until numOfCommands) yield {
      CasElasResponse(

        cmwell.util.concurrent.spinCheck(500.millis, true)(irwService.readPathAsync(s"/cmt/cm/bg-test/circumvented_bg/info$i")) {
          case FullBox(readInfoton) => true
          case _ => false
        },
        null,
        i)
    }

    cmwell.util.concurrent.spinCheck(1.seconds, true, 1.minutes)(ftsServiceES.thinSearch(
      pathFilter = None,
      fieldsFilter = Some(SingleFieldFilter(Must, Equals, "system.parent.parent_hierarchy", Some("/cmt/cm/bg-test/circumvented_bg"))),
      datesFilter = None,
      paginationParams = PaginationParams(0, 2000))) {
      case x: FTSThinSearchResponse => x.length == 1500
      case _ => false
    }.map{ searchResponse =>
      withClue(searchResponse, s"/cmt/cm/bg-test/circumvented_bg") {
        searchResponse.length should equal(1500)
      }
    }
//    val allCasandraAssertionResults = casElasResultList.map(res => assertCasResponse(res))
//    val allRes = Future.sequence(allCasandraAssertionResults).map(res=> res.forall(i => i == succeed))
//    allRes.map(res=> if (res) succeed else fail())

  }

  private def assertCasResponse(casElasticResponse:CasElasResponse) = {

    val i = casElasticResponse.index
    casElasticResponse.casandraRes.map {res=>
      withClue(res, s"/cmt/cm/bg-test/circumvented_bg/info$i") {
        res should not be empty
      }
    }
  }

  override def afterAll() = {
    cmwellBGActor ! ShutDown
    ftsServiceES.shutdown()
    testIRWMockupService = null
    irwService = null
  }
}
