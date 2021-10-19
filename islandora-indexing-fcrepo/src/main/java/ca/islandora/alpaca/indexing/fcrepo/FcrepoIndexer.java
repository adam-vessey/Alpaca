/*
 * Licensed to Islandora Foundation under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * The Islandora Foundation licenses this file to you under the MIT License.
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://opensource.org/licenses/MIT
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ca.islandora.alpaca.indexing.fcrepo;

import static org.apache.camel.LoggingLevel.DEBUG;
import static org.apache.camel.LoggingLevel.ERROR;
import static org.apache.camel.LoggingLevel.INFO;
import static org.apache.camel.LoggingLevel.TRACE;
import static org.apache.camel.LoggingLevel.WARN;
import static org.slf4j.LoggerFactory.getLogger;

import org.apache.camel.Exchange;
import org.apache.camel.Predicate;
import org.apache.camel.builder.PredicateBuilder;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.http.base.HttpOperationFailedException;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;

import ca.islandora.alpaca.support.event.AS2Event;
import ca.islandora.alpaca.support.exceptions.MissingCanonicalUrlException;
import ca.islandora.alpaca.support.exceptions.MissingJsonUrlException;
import ca.islandora.alpaca.support.exceptions.MissingJsonldUrlException;

/**
 * Camel Route to index Drupal nodes into Fedora.
 *
 * @author Danny Lamb
 * @author whikloj
 */
public class FcrepoIndexer extends RouteBuilder {

    @Autowired
    private FcrepoIndexerOptions config;

    /**
     * The Logger.
     */
    private static final Logger LOGGER = getLogger(FcrepoIndexer.class);


    @Override
    public void configure() {
        LOGGER.info("FcrepoIndexer routes starting");

        final Predicate is412 = PredicateBuilder.toPredicate(simple("${exception.statusCode} == 412"));
        final Predicate is404 = PredicateBuilder.toPredicate(simple("${exception.statusCode} == 404"));
        final Predicate is410 = PredicateBuilder.toPredicate(simple("${exception.statusCode} == 410"));

        onException(HttpOperationFailedException.class)
                .onWhen(is412)
                .useOriginalMessage()
                .handled(true)
                .log(
                        INFO,
                        LOGGER,
                        "Received 412 from Milliner, skipping indexing."
                );

        onException(HttpOperationFailedException.class)
                .onWhen(is410)
                .useOriginalMessage()
                .handled(true)
                .log(
                        WARN,
                        LOGGER,
                        "Received 410 from Milliner (object has already been deleted), skipping processing."
                );

        onException(MissingJsonldUrlException.class)
                .useOriginalMessage()
                .handled(true)
                .log(
                        WARN,
                        LOGGER,
                        "Could not locate the Json Url for the object, skipping processing."
                );

        onException(Exception.class)
                .maximumRedeliveries(config.getMaxRedeliveries())
                .log(
                        ERROR,
                        LOGGER,
                        "Error indexing resource in fcrepo: ${exception.message}\n\n${exception.stacktrace}"
                );
        from(config.getNodeIndex())
                .routeId("FcrepoIndexerNode")

                // Parse the event into a POJO.
                .unmarshal().json(JsonLibrary.Jackson, AS2Event.class)

                // Extract relevant data from the event.
                .setProperty("event").simple("${body}")
                .setProperty("uuid").simple("${exchangeProperty.event.object.id.replaceAll(\"urn:uuid:\",\"\")}")
                .setProperty("jsonldUrl").simple("${exchangeProperty.event.object.getJsonldUrl().href}")
                .setProperty("fedoraBaseUrl").simple("${exchangeProperty.event.target}")
                .log(DEBUG, LOGGER, "Received Node event for UUID (${exchangeProperty.uuid}), jsonld URL (" +
                        "${exchangeProperty.jsonldUrl}), fedora base URL (${exchangeProperty.fedoraBaseUrl})")

                // Prepare the message.
                .removeHeaders("*", "Authorization")
                .setHeader(Exchange.HTTP_METHOD, constant("POST"))
                .setHeader("Content-Location", simple("${exchangeProperty.jsonldUrl}"))
                .setHeader(config.getFedoraUriHeader(), exchangeProperty("fedoraBaseUrl"))
                .setBody(constant(null))

                .multicast().parallelProcessing()
                    .to("seda:nodeIndex", "seda:nodeVersionIndex")
                .end();

        // Dynamic endpoints (ie. toD() ) use more resources if the dynamic part is specified in the actual toD(). By
        // sending to the same hostname and passing the URI in the headers we can save those resources.
        from("seda:nodeIndex")
                .routeId("FcrepoIndexerNodeIndex")
                .setHeader(Exchange.HTTP_URI, simple(config.getMillinerBaseUrl() + "node/${exchangeProperty" +
                        ".uuid}"))
                .toD("http://localhost?connectionClose=true");

        from("seda:nodeVersionIndex")
                .routeId("FcrepoIndexerNodeVersion")
                .log(TRACE, LOGGER, "Node indexer version endpoint, isNewVersion is " +
                        "(${exchangeProperty.event.object.isNewVersion}")
                .filter(simple("${exchangeProperty.event.object.isNewVersion}"))
                    .setHeader(Exchange.HTTP_URI, simple(config.getMillinerBaseUrl() +
                    "node/${exchangeProperty.uuid}/version"))
                    .toD("http://localhost?connectionClose=true")
                .end();

        from(config.getNodeDelete())
                .routeId("FcrepoIndexerDeleteNode")
                .onException(HttpOperationFailedException.class)
                        .onWhen(is404)
                        .useOriginalMessage()
                        .handled(true)
                        .log(
                                INFO,
                                LOGGER,
                                "Received 404 from Milliner, skipping de-indexing."
                        )
                        .end()
                // Parse the event into a POJO.
                .unmarshal().json(JsonLibrary.Jackson, AS2Event.class)

                // Extract relevant data from the event.
                .setProperty("event").simple("${body}")
                .setProperty("uuid").simple("${exchangeProperty.event.object.id.replaceAll(\"urn:uuid:\",\"\")}")
                .setProperty("fedoraBaseUrl").simple("${exchangeProperty.event.target}")
                .log(DEBUG, LOGGER, "Received Node delete event for UUID (${exchangeProperty.uuid}), fedora base URL" +
                        " (${exchangeProperty.fedoraBaseUrl})")

                // Prepare the message.
                .removeHeaders("*", "Authorization")
                .setHeader(Exchange.HTTP_METHOD, constant("DELETE"))
                .setHeader(config.getFedoraUriHeader(), exchangeProperty("fedoraBaseUrl"))
                .setBody(constant(null))

                // Remove the file from Gemini.
                .setHeader(Exchange.HTTP_URI, simple(config.getMillinerBaseUrl() + "node/${exchangeProperty.uuid}"))
                .toD("http://localhost?connectionClose=true");

        from(config.getMediaIndex())
                .routeId("FcrepoIndexerMedia")
                .onException(MissingJsonUrlException.class)
                    .useOriginalMessage()
                    .handled(true)
                    .log(
                        WARN,
                        LOGGER,
                        "Could not locate the Json Url for the media, event could be pre-upload. Skipping processing."
                    )
                .end()

                // Parse the event into a POJO.
                .unmarshal().json(JsonLibrary.Jackson, AS2Event.class)

                // Extract relevant data from the event.
                .setProperty("event").simple("${body}")
                .setProperty("sourceField").simple("${exchangeProperty.event.attachment.content.sourceField}")
                .setProperty("jsonUrl").simple("${exchangeProperty.event.object.getJsonUrl().href}")
                .setProperty("fedoraBaseUrl").simple("${exchangeProperty.event.target}")
                .log(DEBUG, LOGGER, "Received Media event for sourceField (${exchangeProperty.sourceField}), jsonld" +
                        " URL (${exchangeProperty.jsonUrl}), fedora Base URL (${exchangeProperty.fedoraBaseUrl})")

                // Prepare the message.
                .removeHeaders("*", "Authorization")
                .setHeader(Exchange.HTTP_METHOD, constant("POST"))
                .setHeader("Content-Location", simple("${exchangeProperty.jsonUrl}"))
                .setHeader(config.getFedoraUriHeader(), exchangeProperty("fedoraBaseUrl"))
                .setBody(constant(null))

                .multicast().parallelProcessing()
                    .to("seda:mediaIndex", "seda:mediaVersionIndex")
                .end();

        // Dynamic endpoints (ie. toD() ) use more resources if the dynamic part is specified in the actual toD(). By
        // sending to the same hostname and passing the URI in the headers we can save those resources.
        from("seda:mediaIndex")
                .routeId("FcrepoIndexerMediaIndex")
                .setHeader(Exchange.HTTP_URI, simple(config.getMillinerBaseUrl() +
                        "media/${exchangeProperty.sourceField}"))
                .toD("http://localhost?connectionClose=true");

        from("seda:mediaVersionIndex")
                .routeId("FcrepoIndexerMediaIndexVersion")
                .log(TRACE, LOGGER, "Media indexer version endpoint, isNewVersion is " +
                        "(${exchangeProperty.event.object.isNewVersion}")
                .filter(simple("${exchangeProperty.event.object.isNewVersion}"))
                    //pass it to milliner
                    .setHeader(Exchange.HTTP_URI, simple(config.getMillinerBaseUrl() +
                        "media/${exchangeProperty.sourceField}/version"))
                    .toD("http://localhost?connectionClose=true")
                .end();

        from(config.getExternalIndex())
                .routeId("FcrepoIndexerExternalFile")
                .onException(MissingCanonicalUrlException.class)
                    .useOriginalMessage()
                    .handled(true)
                    .log(
                            ERROR,
                            LOGGER,
                            "Unable to index external file to Fedora, missing the Drupal URL."
                    )
                    .end()

                // Parse the event into a POJO.
                .unmarshal().json(JsonLibrary.Jackson, AS2Event.class)

                // Extract relevant data from the event.
                .setProperty("event").simple("${body}")
                .setProperty("uuid").simple("${exchangeProperty.event.object.id.replaceAll(\"urn:uuid:\",\"\")}")
                .setProperty("drupal").simple("${exchangeProperty.event.object.getCanonicalUrl().href}")
                .setProperty("fedoraBaseUrl").simple("${exchangeProperty.event.target}")
                .log(DEBUG, LOGGER, "Received File external event for UUID (${exchangeProperty.uuid}), drupal URL " +
                        "(${exchangeProperty.drupal}), fedora base URL (${exchangeProperty.fedoraBaseUrl})")

                // Prepare the message.
                .removeHeaders("*", "Authorization")
                .setHeader(Exchange.HTTP_METHOD, constant("POST"))
                .setHeader("Content-Location", simple("${exchangeProperty.drupal}"))
                .setHeader(config.getFedoraUriHeader(), exchangeProperty("fedoraBaseUrl"))
                .setBody(constant(null))

                // Pass it to milliner.
                .setHeader(Exchange.HTTP_URI, simple(config.getMillinerBaseUrl() +
                        "external/${exchangeProperty.uuid}"))
                .toD("http://localhost?connectionClose=true");

    }
}
