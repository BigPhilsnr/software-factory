package dev.softwarefactory.operator.web;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class LocalOperatorFilterTest {
    private int status(String path, String origin, String type, String token) throws Exception {
        var headers=new java.util.HashMap<String,String>();
        if(origin!=null) headers.put("Origin",origin);
        if(token!=null) headers.put("X-Factory-Token",token);
        var values=Map.<String,Object>of("getMethod","POST","getRequestURI",path,"getServerName","localhost","getScheme","http","getServerPort",8000,"getContentType",type);
        HttpServletRequest request=(HttpServletRequest) Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{HttpServletRequest.class},(proxy,method,args)->{
            if(method.getName().equals("getHeader")) return headers.get(args[0]);
            return values.get(method.getName());
        });
        var status=new AtomicInteger();
        HttpServletResponse response=(HttpServletResponse) Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{HttpServletResponse.class},(proxy,method,args)->{
            if(method.getName().equals("sendError")) status.set((Integer)args[0]);
            return null;
        });
        new FactoryWebConfiguration().localOperatorFilter(new OperatorToken("expected")).doFilter(request,response,(req,res)->status.set(200));
        return status.get();
    }
    @Test void protectsAdkMutationsThroughOriginAndContentTypeAndOperatorMutationsThroughToken() throws Exception {
        assertEquals(403,status("/run","https://attacker.example","application/json",null));
        assertEquals(415,status("/run",null,"application/x-www-form-urlencoded",null));
        assertEquals(200,status("/run","http://localhost:8000","application/json",null));
        assertEquals(403,status("/factory/api/runs",null,"application/json",null));
        assertEquals(200,status("/factory/api/runs",null,"application/json","expected"));
    }
}
