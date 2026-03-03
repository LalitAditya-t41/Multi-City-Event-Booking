package com.eventplatform.shared.eventbrite.webhook;

import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eventplatform.shared.eventbrite.exception.EbWebhookSignatureException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import com.eventplatform.shared.SharedTestApplication;

@SpringBootTest(
    classes = SharedTestApplication.class,
    properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
            "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
        "eventbrite.webhook.secret=test-secret"
    }
)
@AutoConfigureMockMvc(addFilters = false)
class EbWebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EbWebhookDispatcher dispatcher;

    @Test
    void should_return_401_when_signature_invalid() throws Exception {
        doThrow(new EbWebhookSignatureException("Invalid"))
            .when(dispatcher).dispatch(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());

        mockMvc.perform(post("/admin/v1/webhooks/eventbrite")
                .header("X-Eventbrite-Signature", "bad")
                .content("{}"))
            .andExpect(status().isUnauthorized());
    }
}
