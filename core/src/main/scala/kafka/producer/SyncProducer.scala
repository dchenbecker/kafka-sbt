/**
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

package kafka.producer

import kafka.api._
import kafka.network.{BlockingChannel, BoundedByteBufferSend, Receive}
import kafka.utils._
import java.util.Random
import java.util.concurrent.TimeUnit
import kafka.metrics.{KafkaTimer, KafkaMetricsGroup}

object SyncProducer {
  val RequestKey: Short = 0
  val randomGenerator = new Random
}

/*
 * Send a message set.
 */
@threadsafe
class SyncProducer(val config: SyncProducerConfig) extends Logging {

  private val lock = new Object()
  @volatile private var shutdown: Boolean = false
  private val blockingChannel = new BlockingChannel(config.host, config.port, BlockingChannel.UseDefaultBufferSize,
    config.bufferSize, config.requestTimeoutMs)
  val producerRequestStats = new ProducerRequestStats(config.clientId + "-host_%s-port_%s".format(config.host, config.port))

  trace("Instantiating Scala Sync Producer")

  private def verifyRequest(request: RequestOrResponse) = {
    /**
     * This seems a little convoluted, but the idea is to turn on verification simply changing log4j settings
     * Also, when verification is turned on, care should be taken to see that the logs don't fill up with unnecessary
     * data. So, leaving the rest of the logging at TRACE, while errors should be logged at ERROR level
     */
    if (logger.isDebugEnabled) {
      val buffer = new BoundedByteBufferSend(request).buffer
      trace("verifying sendbuffer of size " + buffer.limit)
      val requestTypeId = buffer.getShort()
      if(requestTypeId == RequestKeys.ProduceKey) {
        val request = ProducerRequest.readFrom(buffer)
        trace(request.toString)
      }
    }
  }

  /**
   * Common functionality for the public send methods
   */
  private def doSend(request: RequestOrResponse): Receive = {
    lock synchronized {
      verifyRequest(request)
      getOrMakeConnection()

      var response: Receive = null
      try {
        blockingChannel.send(request)
        response = blockingChannel.receive()
      } catch {
        case e: java.io.IOException =>
          // no way to tell if write succeeded. Disconnect and re-throw exception to let client handle retry
          disconnect()
          throw e
        case e => throw e
      }
      response
    }
  }

  /**
   * Send a message
   */
  def send(producerRequest: ProducerRequest): ProducerResponse = {
    producerRequestStats.requestSizeHist.update(producerRequest.sizeInBytes)
    var response: Receive = null
    producerRequestStats.requestTimer.time {
      response = doSend(producerRequest)
    }
    ProducerResponse.readFrom(response.buffer)
  }

  def send(request: TopicMetadataRequest): TopicMetadataResponse = {
    val response = doSend(request)
    TopicMetadataResponse.readFrom(response.buffer)
  }

  def close() = {
    lock synchronized {
      disconnect()
      shutdown = true
    }
  }

  private def reconnect() {
    disconnect()
    connect()
  }

  /**
   * Disconnect from current channel, closing connection.
   * Side effect: channel field is set to null on successful disconnect
   */
  private def disconnect() {
    try {
      if(blockingChannel.isConnected) {
        info("Disconnecting from " + config.host + ":" + config.port)
        blockingChannel.disconnect()
      }
    } catch {
      case e: Exception => error("Error on disconnect: ", e)
    }
  }
    
  private def connect(): BlockingChannel = {
    if (!blockingChannel.isConnected && !shutdown) {
      try {
        blockingChannel.connect()
        info("Connected to " + config.host + ":" + config.port + " for producing")
      } catch {
        case e: Exception => {
          disconnect()
          error("Producer connection to " +  config.host + ":" + config.port + " unsuccessful", e)
          throw e
        }
      }
    }
    blockingChannel
  }

  private def getOrMakeConnection() {
    if(!blockingChannel.isConnected) {
      connect()
    }
  }
}

class ProducerRequestStats(clientId: String) extends KafkaMetricsGroup {
  val requestTimer = new KafkaTimer(newTimer(clientId + "-ProduceRequestRateAndTimeMs", TimeUnit.MILLISECONDS, TimeUnit.SECONDS))
  val requestSizeHist = newHistogram(clientId + "-ProducerRequestSize")
}
