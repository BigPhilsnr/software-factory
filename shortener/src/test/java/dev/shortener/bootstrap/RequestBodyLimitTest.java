package dev.shortener.bootstrap;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;
class RequestBodyLimitTest {
    @Test void blocksOversizedBodiesBeforeJsonParsing() throws Exception {
        var request=new MockHttpServletRequest("POST","/api/shorten");
        request.setContent(new byte[65537]);
        var response=new MockHttpServletResponse();
        new RequestBodyLimit().doFilter(request,response,(req,res)->fail("JSON parsing must not run"));
        assertEquals(413,response.getStatus());
    }
    @Test void preservesSmallBodyForTheController() throws Exception {
        var request=new MockHttpServletRequest("POST","/api/shorten");
        request.setContent("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        new RequestBodyLimit().doFilter(request,new MockHttpServletResponse(),(req,res)->assertEquals("{}",new String(req.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)));
    }
}
