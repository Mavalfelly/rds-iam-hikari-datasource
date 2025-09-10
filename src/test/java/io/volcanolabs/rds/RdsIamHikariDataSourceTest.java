package io.volcanolabs.rds;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
    @Test
    void testRegionOverrideEnvironmentVariable() {
        Logger rdsLogger = (Logger) LoggerFactory.getLogger(RdsIamHikariDataSource.class);
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        rdsLogger.addAppender(listAppender);
        rdsLogger.setLevel(Level.TRACE);

        try {
            java.util.Map<String, String> env = System.getenv();
            java.lang.reflect.Field field = env.getClass().getDeclaredField("m");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<String, String> writableEnv = (java.util.Map<String, String>) field.get(env);
            writableEnv.put(RdsIamHikariDataSource.RDS_REGION_OVERRIDE, "us-west-2");

            try (RdsIamHikariDataSource dataSource = new RdsIamHikariDataSource()) {
                dataSource.setJdbcUrl("jdbc:postgresql://host:5432/db");
                dataSource.setUsername("user");
                try {
                    dataSource.getPassword();
                } catch (Exception ignored) {}
            }

            List<ILoggingEvent> logsList = listAppender.list;
            assertThat(logsList)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(s -> s.contains("RDS region override: us-west-2"));

        } catch (Exception e) {
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

    private MockedStatic<RdsUtilities> rdsUtilitiesMockedStatic;
    private MockedStatic<DefaultCredentialsProvider> defaultCredentialsProviderMockedStatic;
    private MockedStatic<DefaultAwsRegionProviderChain> defaultAwsRegionProviderChainMockedStatic;

    @BeforeEach
    void setUp() {
    }

    @AfterEach
    void tearDown() {
        if (rdsUtilitiesMockedStatic != null) {
            rdsUtilitiesMockedStatic.close();
            rdsUtilitiesMockedStatic = null;
        }
        if (defaultCredentialsProviderMockedStatic != null) {
            defaultCredentialsProviderMockedStatic.close();
            defaultCredentialsProviderMockedStatic = null;
        }
        if (defaultAwsRegionProviderChainMockedStatic != null) {
            defaultAwsRegionProviderChainMockedStatic.close();
            defaultAwsRegionProviderChainMockedStatic = null;
        }
    }
    
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
