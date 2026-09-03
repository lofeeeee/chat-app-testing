package app.singular.media

import app.singular.config.SingularProperties
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchBucketException
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.net.URI

data class StoredObject(val key: String, val sizeBytes: Long, val contentType: String?)

/**
 * S3-compatible object storage.
 *
 * Written against the AWS SDK with an endpoint override, so the same code runs against
 * self-hosted MinIO today and real S3 later by changing configuration rather than code.
 *
 * The important property of this design: **bytes never pass through the application.** Clients
 * upload straight to storage with a presigned PUT and download with a presigned GET. Streaming
 * a 100 MB video through a GraphQL server would tie up a request thread for the duration of
 * someone's upload, and give you nothing for it.
 */
@Service
class StorageService(private val props: SingularProperties) {

    private val config = props.storage

    private val credentials = StaticCredentialsProvider.create(
        AwsBasicCredentials.create(config.accessKey, config.secretKey)
    )

    private val serviceConfig = S3Configuration.builder()
        // MinIO addresses buckets as /bucket/key rather than bucket.host/key.
        .pathStyleAccessEnabled(config.pathStyle)
        .build()

    private val client: S3Client = S3Client.builder()
        .endpointOverride(URI.create(config.endpoint))
        .region(Region.of(config.region))
        .credentialsProvider(credentials)
        .serviceConfiguration(serviceConfig)
        .build()

    private val presigner: S3Presigner = S3Presigner.builder()
        .endpointOverride(URI.create(config.endpoint))
        .region(Region.of(config.region))
        .credentialsProvider(credentials)
        .serviceConfiguration(serviceConfig)
        .build()

    /**
     * Creates the bucket if it isn't there.
     *
     * Failure is logged rather than thrown: the rest of the app works perfectly well without
     * storage, and refusing to boot because MinIO is slow to start would make every developer's
     * morning worse for a subsystem most requests never touch.
     */
    @PostConstruct
    fun ensureBucket() {
        val exists = try {
            client.headBucket(HeadBucketRequest.builder().bucket(config.bucket).build())
            true
        } catch (_: NoSuchBucketException) {
            false
        } catch (e: Exception) {
            // MinIO answers 403 for HEAD on a bucket that doesn't exist, where S3 answers 404.
            // Treating only NoSuchBucketException as "missing" therefore never creates the
            // bucket on MinIO — it just logs a permissions error that isn't one.
            LOG.debug("HEAD bucket '{}' failed ({}); attempting create", config.bucket, e.message)
            false
        }

        if (exists) {
            LOG.info("Object storage ready: {} bucket '{}'", config.endpoint, config.bucket)
            return
        }

        try {
            client.createBucket(CreateBucketRequest.builder().bucket(config.bucket).build())
            LOG.info("Created bucket '{}' at {}", config.bucket, config.endpoint)
        } catch (e: BucketAlreadyOwnedByYouException) {
            LOG.info("Object storage ready: {} bucket '{}'", config.endpoint, config.bucket)
        } catch (e: Exception) {
            // Logged, never thrown. Most requests never touch storage, and refusing to boot
            // because MinIO is slow to start would make every developer's morning worse.
            LOG.warn(
                "Object storage unavailable at {} — uploads will fail until it is up ({})",
                config.endpoint, e.message,
            )
        }
    }

    /**
     * A time-limited URL the client can PUT to directly.
     *
     * `contentType` and `contentLength` are signed into the URL, so the client cannot upload
     * something other than what it declared. That is the whole reason the server bothers to
     * ask for them up front rather than reading them afterwards.
     */
    fun presignUpload(key: String, contentType: String, contentLength: Long): String {
        val put = PutObjectRequest.builder()
            .bucket(config.bucket)
            .key(key)
            .contentType(contentType)
            .contentLength(contentLength)
            .build()

        return presigner.presignPutObject(
            PutObjectPresignRequest.builder()
                .signatureDuration(config.uploadUrlTtl)
                .putObjectRequest(put)
                .build()
        ).url().toString()
    }

    /**
     * A time-limited read URL.
     *
     * Deliberately short-lived rather than making the bucket public: a leaked URL expires,
     * a public bucket does not.
     */
    fun presignDownload(key: String): String =
        presigner.presignGetObject(
            GetObjectPresignRequest.builder()
                .signatureDuration(config.downloadUrlTtl)
                .getObjectRequest(GetObjectRequest.builder().bucket(config.bucket).key(key).build())
                .build()
        ).url().toString()

    /** Confirms an object exists and reports what is actually there, not what was promised. */
    fun head(key: String): StoredObject? = try {
        val response = client.headObject(
            HeadObjectRequest.builder().bucket(config.bucket).key(key).build()
        )
        StoredObject(key, response.contentLength(), response.contentType())
    } catch (_: NoSuchKeyException) {
        null
    } catch (e: Exception) {
        LOG.warn("HEAD failed for {}: {}", key, e.message)
        null
    }

    fun download(key: String): ByteArray? = try {
        client.getObjectAsBytes(
            GetObjectRequest.builder().bucket(config.bucket).key(key).build()
        ).asByteArray()
    } catch (_: NoSuchKeyException) {
        null
    } catch (e: Exception) {
        LOG.warn("GET failed for {}: {}", key, e.message)
        null
    }

    fun upload(key: String, bytes: ByteArray, contentType: String) {
        client.putObject(
            PutObjectRequest.builder()
                .bucket(config.bucket).key(key).contentType(contentType)
                .contentLength(bytes.size.toLong())
                .build(),
            RequestBody.fromBytes(bytes),
        )
    }

    fun delete(key: String) {
        runCatching {
            client.deleteObject(DeleteObjectRequest.builder().bucket(config.bucket).key(key).build())
        }.onFailure { LOG.warn("DELETE failed for {}: {}", key, it.message) }
    }

    private companion object {
        val LOG = LoggerFactory.getLogger(StorageService::class.java)!!
    }
}
