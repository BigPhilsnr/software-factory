package dev.shortener.platform.http;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import dev.shortener.WebSliceTest;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

/** How failures no story raises on purpose become problem documents. */
class ApiErrorsTest extends WebSliceTest {
    private static final String INTERNAL_ERROR = "internal_error";

    @Test
    void unknownPathIsNotFound() throws Exception {
        expectProblem(mvc.perform(get("/no/such/path")), 404, "not_found");
    }

    @Test
    void transientDatabaseFailureIsUnavailableWithRetryAfter() throws Exception {
        when(links.findByCode(LINK.code())).thenThrow(new CannotGetJdbcConnectionException("pool exhausted"));
        var result = mvc.perform(get("/" + LINK.code()));
        expectProblem(result, 503, "temporarily_unavailable");
        result.andExpect(header().string("Retry-After", "5"));
    }

    @Test
    void permanentDatabaseAndProgrammingFailuresAreServerErrorsThatLeakNoDetail() throws Exception {
        when(links.findByCode("broken01"))
                .thenThrow(new BadSqlGrammarException("find", "SELECT", new SQLException("bad")));
        when(links.findByCode("broken02")).thenThrow(new IllegalStateException("bug"));
        for (String code : new String[] {"broken01", "broken02"}) {
            var result = mvc.perform(get("/" + code));
            expectProblem(result, 500, INTERNAL_ERROR);
            result.andExpect(jsonPath("$.detail").value("Internal server error"));
        }
    }
}
