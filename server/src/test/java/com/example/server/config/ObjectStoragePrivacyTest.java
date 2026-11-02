package com.example.server.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetBucketPolicyResponse;

import java.util.function.Consumer;

class ObjectStoragePrivacyTest {
    @Test
    void refusesAnonymousReadPoliciesInsteadOfStartingWithLeakedPrivateMedia() {
        var s3 = mock(S3Client.class);
        when(s3.getBucketPolicy(any(Consumer.class)))
                .thenReturn(
                        GetBucketPolicyResponse.builder()
                                .policy(
                                        "{\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":[\"*\"]},\"Action\":[\"s3:GetObject\"]}]}"
                                            + " ")
                                .build());
        assertThatThrownBy(() -> new ObjectStorageConfig().ensurePrivateBucket(s3, "private-media"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("anonymous");
        verify(s3, never()).deleteBucketPolicy(any(Consumer.class));
    }

    @Test
    void explicitDenyIsNotTreatedAsAnAnonymousGrant() {
        var s3 = mock(S3Client.class);
        when(s3.getBucketPolicy(any(Consumer.class)))
                .thenReturn(
                        GetBucketPolicyResponse.builder()
                                .policy(
                                        "{\"Statement\":[{\"Effect\":\"Deny\",\"Principal\":\"*\"}]}"
                                            + " ")
                                .build());
        new ObjectStorageConfig().ensurePrivateBucket(s3, "private-media");
    }
}
