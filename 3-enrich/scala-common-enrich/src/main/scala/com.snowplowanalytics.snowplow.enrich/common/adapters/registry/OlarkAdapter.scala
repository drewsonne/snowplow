/*
 * Copyright (c) 2016 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics
package snowplow
package enrich
package common
package adapters
package registry

// Java
import java.net.URI

import org.apache.http.client.utils.URLEncodedUtils
import org.apache.http.NameValuePair
import org.joda.time.DateTime

// Scala
import scala.util.matching.Regex
import scala.util.control.NonFatal
import scala.collection.JavaConversions._

// Scalaz
import scalaz._
import Scalaz._

// Jackson
import com.fasterxml.jackson.core.JsonParseException

// json4s
import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import org.json4s.scalaz.JsonScalaz._

// Iglu
import iglu.client.{
  SchemaKey,
  Resolver
}

// This project
import loaders.CollectorPayload
import utils.{JsonUtils => JU}

/**
 * Transforms a collector payload which conforms to
 * a known version of the Olark Tracking webhook
 * into raw events.
 */
object OlarkAdapter extends Adapter {

  // Vendor name for Failure Message
  private val VendorName = "Olark"

  // Tracker version for an Olark Tracking webhook
  private val TrackerVersion = "com.olark-v1"

  // Expected content type for a request body
  private val ContentType = "application/x-www-form-urlencoded"

  // Schemas for reverse-engineering a Snowplow unstructured event
  private val EventSchemaMap = Map (
    "transcript" -> SchemaKey("com.olark", "transcript", "jsonschema", "1-0-0").toSchemaUri,
    "offline_message" -> SchemaKey("com.olark", "offline_message", "jsonschema", "1-0-0").toSchemaUri
  )

  /**
   * Converts a CollectorPayload instance into raw events.
   *
   * An Olark Tracking payload contains one single event
   * in the body of the payload, stored within a HTTP encoded
   * string.
   *
   * @param payload The CollectorPayload containing one or more
   *        raw events as collected by a Snowplow collector
   * @param resolver (implicit) The Iglu resolver used for
   *        schema lookup and validation. Not used
   * @return a Validation boxing either a NEL of RawEvents on
   *         Success, or a NEL of Failure Strings
   */
  def toRawEvents(payload: CollectorPayload)(implicit resolver: Resolver): ValidatedRawEvents = {
    (payload.body, payload.contentType) match {
      case (None, _)                          => s"Request body is empty: no ${VendorName} events to process".failNel
      case (_, None)                          => s"Request body provided but content type empty, expected ${ContentType} for ${VendorName}".failNel
      case (_, Some(ct)) if ct != ContentType => s"Content type of ${ct} provided, expected ${ContentType} for ${VendorName}".failNel
      case (Some(body),_) if (body.isEmpty)   => s"${VendorName} event body is empty: nothing to process".failNel
      case (Some(body),_)                     => {
        try {
          val qsParams = toMap(payload.querystring)
          val bodyMap = toMap(URLEncodedUtils.parse(URI.create("http://localhost/?" + body), "UTF-8").toList)
          (payloadBodyToEvent(bodyMap)) match {
            case (Failure(err))    => err.failNel
            case (Success(event))  => {
              val hasOperators = (event \ "operators")
              val eventType = hasOperators match {
                case (JNothing) => Some("offline_message")
                case (_)        => Some("transcript")
              }
              lookupSchema(eventType, VendorName, EventSchemaMap) match {
                case Failure(str)    => str.fail
                case Success(schema) => {
                  val transformedEvent = transformTimestamps(event)
                  NonEmptyList(RawEvent(
                    api          = payload.api,
                    parameters   = toUnstructEventParams(TrackerVersion, qsParams, schema, transformedEvent, "srv"),
                    contentType  = payload.contentType,
                    source       = payload.source,
                    context      = payload.context
                  )).success
                }
              }
            }
          }
        } catch {
          case NonFatal(e) => s"${VendorName} could not parse body: [${JU.stripInstanceEtc(e.getMessage).orNull}]".failNel
        }
      }
    }
  }
  /**
   * Converts all olark timestamps in a parsed transcript or offline_message json object to iso8601 strings
   *
   * @param json a parsed event
   * @return JObject the event with timstamps replaced
   */
  private def transformTimestamps(json: JValue): JValue = {
    json.transformField {
      case JField("items", jArray) => ("items", jArray.transform {
        case jo: JObject => jo.transformField {
          case JField("timestamp", JString(value)) => ("timestamp", toIso8601(toMsec(value)))
        }
      })
    }
  }
  /**
   * Converts an Olark timestamp (e.g. 1307116657.1) to msec
   *
   * @param oTs olark timestamp string
   * @return Long msec
   * @throws MatchError and NumberFormatException
   */
  private def toMsec(oTs: String): Long =
    (oTs.split('.') match {
      case Array(sec) => s"${sec}000"
      case Array(sec, msec) => s"${sec}${msec.take(3).padTo(3, '0')}"
    }).toLong
  /**
   * Converts epoch msec to Iso8601 Jstring
   *
   * @param msec Long epocj msec
   * @return JString iso8601
   */
  private def toIso8601(msec: Long): JString = {
    val dt: DateTime = new DateTime(msec)
    JString(JsonSchemaDateTimeFormat.print(dt))
  }
  /**
   * Converts a querystring payload into an event
   * @param bodyMap The converted map from the querystring
   */
  private def payloadBodyToEvent(bodyMap: Map[String, String]): Validation[String, JObject] = {
    bodyMap.get("data") match {
      case None       => s"${VendorName} event data does not have 'data' as a key".fail
      case Some("")   => s"${VendorName} event data is empty: nothing to process".fail
      case Some(json) => {
        try {
          val event = parse(json)
          event match {
            case obj: JObject => obj.success
            case _            => s"${VendorName} event wrong type: [%s]".format(event.getClass).fail
          }
        } catch {
          case e: JsonParseException => {
              val exception = JU.stripInstanceEtc(e.getMessage).orNull
              s"${VendorName} event string failed to parse into JSON: [${exception}]".fail
          }
          case NonFatal(e) => {
            val exception = JU.stripInstanceEtc(e.getMessage).orNull
            s"${VendorName} incorrect event string : [${exception}]".fail
          }
        }
      }
    }
  }
}