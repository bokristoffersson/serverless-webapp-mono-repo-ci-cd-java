package com.webapp.function;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.rekognition.model.FaceMatch;
import software.amazon.awssdk.services.rekognition.model.Image;
import software.amazon.awssdk.services.rekognition.model.SearchFacesByImageResponse;
import software.amazon.lambda.powertools.logging.PowertoolsLogging;
import software.amazon.lambda.powertools.tracing.PowertoolsTracing;

import static java.util.Collections.emptyList;

public class RecognizeImageHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private static final Logger LOG = LogManager.getLogger(RecognizeImageHandler.class);

    private static final String COLLECTION_ID = System.getenv("CollectionName");
    private static final String TABLE_NAME = System.getenv("TableName");

    private static final RekognitionClient rekognitionClient = RekognitionClient.create();
    private static final DynamoDbClient dynamoDbClient = DynamoDbClient.create();

    @Override
    @PowertoolsLogging(logEvent = true, samplingRate = 0.5)
    @PowertoolsTracing(namespace = "Recognition")
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        String image = input.getBody();

        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Access-Control-Allow-Methods", "POST,GET");
        APIGatewayProxyResponseEvent apiGatewayProxyResponseEvent = new APIGatewayProxyResponseEvent()
                .withHeaders(headers);

        if (image == null || image.isEmpty()) {
            LOG.debug("No Image found in payload");
            return apiGatewayProxyResponseEvent
                    .withStatusCode(400)
                    .withBody("{\n" +
                            "  \"message\": \"No image found in body. Pass base 64 encode image in the request body\"\n" +
                            "}");
        }


        byte[] decodedImage = Base64.getDecoder().decode(image);

        List<FaceMatch> faceMatches = faceSearch(decodedImage);

        if (faceMatches.size() > 0) {
            FaceMatch faceMatch = faceMatches.get(0);

            LOG.debug("Details of matched face: {}", faceMatch);

            Map<String, AttributeValue> keyMap = new HashMap<>();

            keyMap.put("RekognitionId", AttributeValue.builder()
                    .s(faceMatch.face().faceId())
                    .build());

            GetItemResponse faceDetails = query(keyMap);

            if (faceDetails.hasItem()) {
                return apiGatewayProxyResponseEvent
                        .withStatusCode(200)
                        .withBody("{\n" +
                                "  \"person_name\": " + faceDetails.item().get("FullName").s() + "\n" +
                                "}");
            }
        }

        return apiGatewayProxyResponseEvent
                .withStatusCode(200)
                .withBody("{\n" +
                        "  \"message\": \"No match found in the record\"\n" +
                        "}");
    }

    @PowertoolsTracing
    private GetItemResponse query(Map<String, AttributeValue> keyMap) {
        GetItemRequest itemRequest = GetItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(keyMap)
                .build();
        try {
            return dynamoDbClient.getItem(itemRequest);
        } catch (Exception e) {
            LOG.error("Failed querying dynamo tab for query {}", itemRequest, e);
            throw e;
        }
    }

    @PowertoolsTracing
    private List<FaceMatch> faceSearch(byte[] decodedImage) {
        try {
            SearchFacesByImageResponse searchFacesByImageResponse = rekognitionClient.searchFacesByImage(builder -> builder.collectionId(COLLECTION_ID)
                    .image(Image.builder()
                            .bytes(SdkBytes.fromByteArray(decodedImage)).build())
                    .maxFaces(1)
                    .faceMatchThreshold(90f));

            LOG.debug("Service response for find face {}", searchFacesByImageResponse);

            return searchFacesByImageResponse
                    .faceMatches();
        } catch (Exception e) {
            LOG.error("Failed getting find face result. Reason: {}", e.getMessage(), e);
            return emptyList();
        }
    }
}
