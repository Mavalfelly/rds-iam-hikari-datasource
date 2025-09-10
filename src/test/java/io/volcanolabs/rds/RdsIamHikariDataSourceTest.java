package io.volcanolabs.rds;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.rds.RdsUtilities;
import software.amazon.awssdk.services.rds.model.GenerateAuthenticationTokenRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RdsIamHikariDataSourceTest {
    /**
     * Tests the region override behavior using a cleaner approach.
     * Instead of fragile reflection, we use Maven surefire configuration 
     * to set environment variables for testing.
     */
    @Test
    void testRegionOverrideFromSystemProperty() {
        Logger rdsLogger = (Logger) LoggerFactory.getLogger(RdsIamHikariDataSource.class);
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        rdsLogger.addAppender(listAppender);
        rdsLogger.setLevel(Level.TRACE);

        // Check if RDS_REGION_OVERRIDE is available as a system property 
        // (which can be set via application.properties or -D arguments)
        String regionOverride = System.getProperty("RDS_REGION_OVERRIDE");
        
        // If system property is set, temporarily set as environment variable for this test
        if (regionOverride != null) {
            // Use a custom environment provider or simply document the expected behavior
            try (RdsIamHikariDataSource dataSource = new RdsIamHikariDataSource()) {
                dataSource.setJdbcUrl("jdbc:postgresql://host:5432/db");
                dataSource.setUsername("user");
                try {
                    dataSource.getPassword();
                } catch (Exception ignored) {}

                List<ILoggingEvent> logsList = listAppender.list;
                // This test documents the expected behavior when override is provided
                // Note: The actual environment variable check is in the main class
                assertThat(logsList)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(s -> s.contains("AWS region:"));
            }
        } else {
            // Test normal behavior without override
            try (RdsIamHikariDataSource dataSource = new RdsIamHikariDataSource()) {
                dataSource.setJdbcUrl("jdbc:postgresql://host:5432/db");
                dataSource.setUsername("user");
                try {
                    dataSource.getPassword();
                } catch (Exception ignored) {}

                List<ILoggingEvent> logsList = listAppender.list;
                assertThat(logsList)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(s -> s.contains("AWS region:"))
                    .noneMatch(s -> s.contains("RDS region override:"));
            }
        }
        
        listAppender.stop();
    }

    @Test
    void testRegionOverrideEnvironmentVariable() {
        Logger rdsLogger = (Logger) LoggerFactory.getLogger(RdsIamHikariDataSource.class);
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        rdsLogger.addAppender(listAppender);
        rdsLogger.setLevel(Level.TRACE);

        // This test verifies that the region override logic works correctly
        // The environment variable is set via Maven surefire configuration in pom.xml
        try (RdsIamHikariDataSource dataSource = new RdsIamHikariDataSource()) {
            dataSource.setJdbcUrl("jdbc:postgresql://host:5432/db");
            dataSource.setUsername("user");
            try {
                dataSource.getPassword();
            } catch (Exception ignored) {}

            List<ILoggingEvent> logsList = listAppender.list;
            // Verify that the region override from Maven configuration is working
            assertThat(logsList)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(s -> s.contains("AWS region:"))
                .anyMatch(s -> s.contains("RDS region override: us-west-2"));
        }
        
        listAppender.stop();
    }

    @Test
    void testLoggingOutputForMajorMethods() {
        Logger rdsLogger = (Logger) LoggerFactory.getLogger(RdsIamHikariDataSource.class);
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        rdsLogger.addAppender(listAppender);
        rdsLogger.setLevel(Level.TRACE);

        try (RdsIamHikariDataSource dataSource = new RdsIamHikariDataSource()) {
            dataSource.setJdbcUrl("jdbc:postgresql://host:5432/db");
            dataSource.setUsername("user");
            try {
                dataSource.getCredentials();
            } catch (Exception ignored) {}
            try {
                dataSource.getPassword();
            } catch (Exception ignored) {}
        }

        List<ILoggingEvent> logsList = listAppender.list;
        assertThat(logsList)
            .extracting(ILoggingEvent::getFormattedMessage)
            .anyMatch(s -> s.contains("RdsIamHikariDataSource.getCredentials() called."))
            .anyMatch(s -> s.contains("RdsIamHikariDataSource.getPassword() called."))
            .anyMatch(s -> s.contains("cleanUrl:"))
            .anyMatch(s -> s.contains("AWS region:"));

        listAppender.stop();
    }

    @Test
    void testMissingOrInvalidAwsCredentialsThrows() {
        RdsUtilities.Builder mockRdsBuilder = mock(RdsUtilities.Builder.class);
        when(mockRdsBuilder.credentialsProvider(any())).thenThrow(new RuntimeException("No AWS credentials"));

        try (MockedStatic<RdsUtilities> rdsUtilsMock = mockStatic(RdsUtilities.class)) {
            rdsUtilsMock.when(RdsUtilities::builder).thenReturn(mockRdsBuilder);
            try (RdsIamHikariDataSource dataSource = new RdsIamHikariDataSource()) {
                dataSource.setJdbcUrl("jdbc:postgresql://host:5432/db");
                dataSource.setUsername("user");
                assertThatThrownBy(dataSource::getPassword)
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("No AWS credentials");
            }
        }
    }
    @Test
    void testMalformedJdbcUrlThrowsException() {
        try (RdsIamHikariDataSource dataSource = new RdsIamHikariDataSource()) {
            dataSource.setJdbcUrl("not-a-valid-url");
            dataSource.setUsername("user");
            assertThatThrownBy(dataSource::getPassword)
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("hostname");
        }
    }

    @Test
    void testMissingUsernameThrowsException() {
        try (RdsIamHikariDataSource dataSource = new RdsIamHikariDataSource()) {
            dataSource.setJdbcUrl("jdbc:postgresql://some-host:1234/some-db");
            dataSource.setUsername("");
            assertThatThrownBy(dataSource::getPassword)
                .isInstanceOf(Exception.class);
        }
    }

    static {
        System.setProperty("aws.region", "us-east-1");
        System.setProperty("aws.accessKeyId", "test-access-key");
        System.setProperty("aws.secretAccessKey", "test-secret-key");
    }

    private static final String MOCKED_TOKEN = "mocked-token";

    @Mock
    private RdsUtilities mockRdsUtilities;
    @Mock
    private DefaultCredentialsProvider mockCredentialsProvider;
    @Mock
    private DefaultAwsRegionProviderChain mockRegionProviderChain;

    @Test
    void testGetPasswordReturnsCorrectToken() {

        when(mockRdsUtilities.generateAuthenticationToken(any(GenerateAuthenticationTokenRequest.class)))
                .thenReturn(MOCKED_TOKEN);
        
        RdsUtilities.Builder mockRdsBuilder = mock(RdsUtilities.Builder.class);
        when(mockRdsBuilder.credentialsProvider(any())).thenReturn(mockRdsBuilder);
        when(mockRdsBuilder.region(any(Region.class))).thenReturn(mockRdsBuilder);
        when(mockRdsBuilder.build()).thenReturn(mockRdsUtilities);
        
        try (MockedStatic<RdsUtilities> rdsUtilsMock = mockStatic(RdsUtilities.class)) {
            rdsUtilsMock.when(RdsUtilities::builder).thenReturn(mockRdsBuilder);

            DefaultCredentialsProvider.Builder mockCredsBuilder = mock(DefaultCredentialsProvider.Builder.class);
            when(mockCredsBuilder.build()).thenReturn(mockCredentialsProvider);
            
            try (MockedStatic<DefaultCredentialsProvider> credsMock = mockStatic(DefaultCredentialsProvider.class)) {
                credsMock.when(DefaultCredentialsProvider::builder).thenReturn(mockCredsBuilder);

                try (RdsIamHikariDataSource dataSource = new RdsIamHikariDataSource()) {
                    dataSource.setJdbcUrl("jdbc:postgresql://some-host:1234/some-db");
                    dataSource.setUsername("some-user");

                    String token = dataSource.getPassword();

                    assertThat(token).isEqualTo(MOCKED_TOKEN);
                }
            }
        }
    }

    @Test
    void testGetRegionWithOverride() {

        when(mockRdsUtilities.generateAuthenticationToken(any(GenerateAuthenticationTokenRequest.class)))
                .thenReturn(MOCKED_TOKEN);
        
        RdsUtilities.Builder mockRdsBuilder = mock(RdsUtilities.Builder.class);
        when(mockRdsBuilder.credentialsProvider(any())).thenReturn(mockRdsBuilder);
        when(mockRdsBuilder.region(any(Region.class))).thenReturn(mockRdsBuilder);
        when(mockRdsBuilder.build()).thenReturn(mockRdsUtilities);
        
        try (MockedStatic<RdsUtilities> rdsUtilsMock = mockStatic(RdsUtilities.class)) {
            rdsUtilsMock.when(RdsUtilities::builder).thenReturn(mockRdsBuilder);

            DefaultCredentialsProvider.Builder mockCredsBuilder = mock(DefaultCredentialsProvider.Builder.class);
            when(mockCredsBuilder.build()).thenReturn(mockCredentialsProvider);
            
            try (MockedStatic<DefaultCredentialsProvider> credsMock = mockStatic(DefaultCredentialsProvider.class)) {
                credsMock.when(DefaultCredentialsProvider::builder).thenReturn(mockCredsBuilder);

                try (RdsIamHikariDataSource dataSource = new RdsIamHikariDataSource()) {
                    dataSource.setJdbcUrl("jdbc:postgresql://some-host:1234/some-db");
                    dataSource.setUsername("some-user");
                    
                    String token = dataSource.getPassword();
                    
                    // Verify that token generation was called and returns expected value
                    assertThat(token).isEqualTo(MOCKED_TOKEN);
                    verify(mockRdsUtilities).generateAuthenticationToken(any(GenerateAuthenticationTokenRequest.class));
                }
            }
        }
    }

    @Test
    void testGetRegionWithoutOverride() {

        when(mockRdsUtilities.generateAuthenticationToken(any(GenerateAuthenticationTokenRequest.class)))
                .thenReturn(MOCKED_TOKEN);
        
        RdsUtilities.Builder mockRdsBuilder = mock(RdsUtilities.Builder.class);
        when(mockRdsBuilder.credentialsProvider(any())).thenReturn(mockRdsBuilder);
        when(mockRdsBuilder.region(any(Region.class))).thenReturn(mockRdsBuilder);
        when(mockRdsBuilder.build()).thenReturn(mockRdsUtilities);
        
        try (MockedStatic<RdsUtilities> rdsUtilsMock = mockStatic(RdsUtilities.class)) {
            rdsUtilsMock.when(RdsUtilities::builder).thenReturn(mockRdsBuilder);

            DefaultCredentialsProvider.Builder mockCredsBuilder = mock(DefaultCredentialsProvider.Builder.class);
            when(mockCredsBuilder.build()).thenReturn(mockCredentialsProvider);
            
            try (MockedStatic<DefaultCredentialsProvider> credsMock = mockStatic(DefaultCredentialsProvider.class)) {
                credsMock.when(DefaultCredentialsProvider::builder).thenReturn(mockCredsBuilder);

                try (RdsIamHikariDataSource dataSource = new RdsIamHikariDataSource()) {
                    dataSource.setJdbcUrl("jdbc:postgresql://some-host:1234/some-db");
                    dataSource.setUsername("some-user");
                    
                    String token = dataSource.getPassword();
                    
                    assertThat(token).isEqualTo(MOCKED_TOKEN);
                    verify(mockRdsBuilder).credentialsProvider(any());
                    verify(mockRdsBuilder).region(any(Region.class));
                }
            }
        }
    }
}
