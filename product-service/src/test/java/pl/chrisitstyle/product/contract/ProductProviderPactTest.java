package pl.chrisitstyle.product.contract;

import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactBroker;
import au.com.dius.pact.provider.junitsupport.loader.PactBrokerConsumerVersionSelectors;
import au.com.dius.pact.provider.junitsupport.loader.SelectorBuilder;
import au.com.dius.pact.provider.spring.spring7.Spring7MockMvcTestTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import pl.chrisitstyle.product.ProductController;
import pl.chrisitstyle.product.ProductReservationResponse;
import pl.chrisitstyle.product.ProductService;
import pl.chrisitstyle.product.StockRequest;
import pl.chrisitstyle.product.exception.ProductUnavailableException;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@WebMvcTest(
        value = ProductController.class,
        excludeAutoConfiguration = {
                OAuth2ResourceServerAutoConfiguration.class,
                OAuth2ResourceServerWebSecurityAutoConfiguration.class
        }
)
@AutoConfigureMockMvc(addFilters = false)
@Provider("product-service")
@PactBroker(
        host = "localhost",
        port = "9292"
)
class ProductProviderPactTest {

    private static final Long PRODUCT_ID = 42L;
    private static final Integer QUANTITY = 2;

    private static final UUID RESERVATION_KEY =
            UUID.fromString(
                    "123e4567-e89b-12d3-a456-426614174000"
            );

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductService productService;

    @PactBrokerConsumerVersionSelectors
    public static SelectorBuilder consumerVersionSelectors() {

        String consumerBranch =
                System.getenv()
                        .getOrDefault(
                                "PACT_CONSUMER_BRANCH",
                                "main"
                        );

        return new SelectorBuilder()
                .branch(
                        consumerBranch
                );
    }

    @BeforeEach
    void setUp(
            PactVerificationContext context
    ) {

        context.setTarget(
                new Spring7MockMvcTestTarget(
                        mockMvc
                )
        );
    }
    
    @State("product 42 has sufficient stock")
    void product42HasSufficientStock() {

        when(
                productService.reserve(
                        eq(PRODUCT_ID),
                        argThat(
                                (StockRequest request) ->
                                        request != null
                                                && QUANTITY.equals(
                                                request.quantity()
                                        )
                        ),
                        any(UUID.class)
                )
        ).thenReturn(
                new ProductReservationResponse(
                        PRODUCT_ID,
                        QUANTITY,
                        new BigDecimal("19.99")
                )
        );
    }

    @State("product 42 has insufficient stock")
    void product42HasInsufficientStock() {

        when(
                productService.reserve(
                        eq(PRODUCT_ID),
                        argThat(
                                (StockRequest request) ->
                                        request != null
                                                && QUANTITY.equals(
                                                request.quantity()
                                        )
                        ),
                        any(UUID.class)
                )
        ).thenThrow(
                new ProductUnavailableException(
                        "Insufficient stock for product 42"
                )
        );
    }

    @State("product 42 has an active reservation")
    void product42HasAnActiveReservation() {

        doAnswer(invocation -> {

            Long productId =
                    invocation.getArgument(0);

            StockRequest request =
                    invocation.getArgument(1);

            UUID reservationKey =
                    invocation.getArgument(2);

            assertThat(productId)
                    .isEqualTo(PRODUCT_ID);

            assertThat(request)
                    .isNotNull();

            assertThat(request.quantity())
                    .isEqualTo(QUANTITY);

            assertThat(reservationKey)
                    .isEqualTo(RESERVATION_KEY);

            return null;

        }).when(productService)
                .release(
                        any(),
                        any(),
                        any()
                );
    }

    @TestTemplate
    @ExtendWith(
            PactVerificationInvocationContextProvider.class
    )
    void verifyPact(
            PactVerificationContext context
    ) {

        context.verifyInteraction();
    }
}