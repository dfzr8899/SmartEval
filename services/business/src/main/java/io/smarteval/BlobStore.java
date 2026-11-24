package io.smarteval;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Component
class BlobStore {
    private final Path root;
    private final S3Client s3;
    private final String bucket;

    BlobStore(@Value("${smarteval.blob-directory}") String directory,@Value("${SMARTEVAL_S3_ENDPOINT:}") String endpoint,
            @Value("${SMARTEVAL_S3_BUCKET:}") String bucket,@Value("${SMARTEVAL_S3_REGION:us-east-1}") String region,
            @Value("${SMARTEVAL_S3_ACCESS_KEY:}") String access,@Value("${SMARTEVAL_S3_SECRET_KEY:}") String secret){
        root=Path.of(directory).toAbsolutePath().normalize();
        this.bucket=bucket;
        if(endpoint.isBlank())s3=null;
        else{
            if(bucket.isBlank()||access.isBlank()||secret.isBlank())throw new IllegalStateException("S3 bucket and service credentials are required");
            s3=S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.of(region)).forcePathStyle(true)
                    .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(4)).socketTimeout(Duration.ofSeconds(8)))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(access,secret)))
                    .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED).responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                    .overrideConfiguration(c->c.apiCallTimeout(Duration.ofSeconds(20)).apiCallAttemptTimeout(Duration.ofSeconds(8))).build();
        }
    }

    private Path path(String key){
        Path file=root.resolve(key).normalize();
        if(!file.startsWith(root))throw new IllegalArgumentException("Invalid object key");
        return file;
    }

    byte[] read(String key,long limit)throws IOException{
        int maximum=Math.toIntExact(limit)+1;
        try(var stream=s3==null?Files.newInputStream(path(key)):s3.getObject(r->r.bucket(bucket).key(key))){
            byte[] value=stream.readNBytes(maximum);
            if(value.length>limit)throw new IOException("Object size exceeded");
            return value;
        }catch(S3Exception e){
            if(e.statusCode()==404)throw new NoSuchFileException(key);
            throw new IOException("Object storage read failed",e);
        }catch(SdkException e){throw new IOException("Object storage read failed",e);}
    }

    void create(String key,byte[] value)throws IOException{
        if(s3==null){
            Path file=path(key);
            Files.createDirectories(file.getParent());
            Files.write(file,value,StandardOpenOption.CREATE_NEW);
            return;
        }
        try{s3.putObject(r->r.bucket(bucket).key(key).ifNoneMatch("*"),RequestBody.fromBytes(value));}
        catch(S3Exception e){
            if(e.statusCode()==412)throw new FileAlreadyExistsException(key);
            throw new IOException("Object storage write failed",e);
        }catch(SdkException e){throw new IOException("Object storage write failed",e);}
    }

    void delete(String key)throws IOException{
        if(s3==null){Files.deleteIfExists(path(key));return;}
        try{s3.deleteObject(r->r.bucket(bucket).key(key));}
        catch(SdkException e){throw new IOException("Object storage deletion failed",e);}
    }

    @jakarta.annotation.PreDestroy
    void close(){if(s3!=null)s3.close();}
}
