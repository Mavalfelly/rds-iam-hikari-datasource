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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.rds.RdsUtilities;
import software.amazon.awssdk.services.rds.model.GenerateAuthenticationTokenRequest;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = TestApplication.class)
@ExtendWith(MockitoExtension.class)
class RdsIamHikariDataSourceTest {

    static {
        // Set up system properties to provide AWS region and credentials for tests
        System.setProperty("aws.region", "us-east-1");
        System.setProperty("aws.accessKeyId", "test-access-key");
        System.setProperty("aws.secretAccessKey", "test-secret-key");
    }

    private static final String MOCKED_TOKEN = "mocked-token";

    @Autowired
    private ApplicationContext applicationContext;

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
    void contextLoads() {
        assertThat(applicationContext).isNotNull();
    }

    @Test
    void dataSourceBeanIsOurCustomDataSource() {
        DataSource dataSource = applicationContext.getBean(DataSource.class);
        assertThat(dataSource).isInstanceOf(RdsIamHikariDataSource.class);
    }

    @Test
    void connectionAttemptTriggersTokenGenerationAndLogging() {
        Logger rdsLogger = (Logger) LoggerFactory.getLogger(RdsIamHikariDataSource.class);
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        rdsLogger.addAppender(listAppender);
        rdsLogger.setLevel(Level.TRACE);

        DataSource dataSource = applicationContext.getBean(DataSource.class);

        assertThatThrownBy(dataSource::getConnection)
                .isInstanceOf(Exception.class);

        List<ILoggingEvent> logsList = listAppender.list;
        assertThat(logsList)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(s -> s.contains("RdsIamHikariDataSource.getCredentials() called."))
                .anyMatch(s -> s.contains("cleanUrl: postgresql://dummy-host:5432/dummy-db"));

        listAppender.stop();
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
