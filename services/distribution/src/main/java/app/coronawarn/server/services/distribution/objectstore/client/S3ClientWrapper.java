/*-
 * ---license-start
 * Corona-Warn-App
 * ---
 * Copyright (C) 2020 SAP SE and all other contributors
 * ---
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ---license-end
 */

package app.coronawarn.server.services.distribution.objectstore.client;

import static java.util.stream.Collectors.toList;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Implementation of {@link ObjectStoreClient} that encapsulates an {@link S3Client}.
 */
public class S3ClientWrapper implements ObjectStoreClient {

  private static final Logger logger = LoggerFactory.getLogger(S3ClientWrapper.class);

  private final S3Client s3Client;

  public S3ClientWrapper(S3Client s3Client) {
    this.s3Client = s3Client;
  }

  @Override
  public boolean bucketExists(String bucketName) {
    try {
      // using S3Client.listObjectsV2 instead of S3Client.listBuckets/headBucket in order to limit required permissions
      s3Client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucketName).maxKeys(0).build());
      return true;
    } catch (NoSuchBucketException e) {
      return false;
    } catch (SdkException e) {
      throw new ObjectStoreOperationFailedException("Failed to determine if bucket exists.", e);
    }
  }

  @Override
  public List<S3Object> getObjects(String bucket, String prefix) {
    try {
      ListObjectsV2Response response =
          s3Client.listObjectsV2(ListObjectsV2Request.builder().prefix(prefix).bucket(bucket).build());
      return response.contents().stream().map(S3ClientWrapper::buildS3Object).collect(toList());
    } catch (SdkException e) {
      throw new ObjectStoreOperationFailedException("Failed to upload object to object store", e);
    }
  }

  @Override
  public void putObject(String bucket, String objectName, Path filePath, Map<HeaderKey, String> headers) {
    RequestBody bodyFile = RequestBody.fromFile(filePath);

    var requestBuilder = PutObjectRequest.builder().bucket(bucket).key(objectName);
    if (headers.containsKey(HeaderKey.AMZ_ACL)) {
      requestBuilder.acl(headers.get(HeaderKey.AMZ_ACL));
    }
    if (headers.containsKey(HeaderKey.CACHE_CONTROL)) {
      requestBuilder.cacheControl(headers.get(HeaderKey.CACHE_CONTROL));
    }

    try {
      s3Client.putObject(requestBuilder.build(), bodyFile);
    } catch (SdkException e) {
      throw new ObjectStoreOperationFailedException("Failed to upload object to object store", e);
    }
  }

  @Override
  public void removeObjects(String bucket, List<String> objectNames) {
    if (objectNames.isEmpty()) {
      return;
    }

    Collection<ObjectIdentifier> identifiers = objectNames.stream()
        .map(key -> ObjectIdentifier.builder().key(key).build()).collect(toList());

    try {
      DeleteObjectsResponse response = s3Client.deleteObjects(
          DeleteObjectsRequest.builder()
              .bucket(bucket)
              .delete(Delete.builder().objects(identifiers).build()).build());

      if (response.hasErrors()) {
        String errMessage = "Failed to remove objects from object store.";
        logger.error("{} {}", errMessage, response.errors());
        throw new ObjectStoreOperationFailedException(errMessage);
      }
    } catch (SdkException e) {
      throw new ObjectStoreOperationFailedException("Failed to remove objects from object store.", e);
    }
  }

  private static S3Object buildS3Object(software.amazon.awssdk.services.s3.model.S3Object s3Object) {
    String etag = s3Object.eTag().replaceAll("\"", "");
    return new S3Object(s3Object.key(), etag);
  }
}
