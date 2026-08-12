package com.wish.rd.engine.requirement.policy;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/** RFC 8785 vectors and fail-closed input contracts for policy digests. */
class CanonicalJsonSha256Test {

    @Test
    void canonicalizesRfc8785NumbersUnicodeObjectKeysAndWhitespace() throws Exception {
        Method digest = digestMethod();

        assertEquals("sha256:7c892d3452ad85ad65857a43e8dcac93b79475d2334fc3e85bac5c599142c158",
                invoke(digest, " { \"numbers\" : [333333333.33333329,1E30,4.50,2e-3,0.000000000000000000000000001] } "));
        assertEquals(invoke(digest, "{\"a\":1,\"€\":2}"), invoke(digest, "{\"€\":2,\"a\":1}"));
    }

    @Test
    void rejectsDuplicateKeysTrailingTokensAndLoneSurrogatesInValuesOrObjectKeys() throws Exception {
        Method digest = digestMethod();

        assertThrows(IllegalArgumentException.class, () -> invoke(digest, "{\"a\":1,\"a\":2}"));
        assertThrows(IllegalArgumentException.class, () -> invoke(digest, "{\"a\":1} true"));
        assertThrows(IllegalArgumentException.class, () -> invoke(digest, "{\"a\":\"\\ud800\"}"));
        assertThrows(IllegalArgumentException.class, () -> invoke(digest, "{\"\\ud800\":1}"));
    }

    private static Method digestMethod() throws Exception {
        try {
            return Class.forName("com.wish.rd.engine.requirement.policy.CanonicalJsonSha256")
                    .getMethod("digest", String.class);
        } catch (ClassNotFoundException missing) {
            fail("missing dedicated CanonicalJsonSha256 utility", missing);
            throw new AssertionError("unreachable");
        }
    }

    private static String invoke(Method method, String json) throws Exception {
        try {
            return (String) method.invoke(null, json);
        } catch (InvocationTargetException failed) {
            throw (Exception) failed.getCause();
        }
    }
}
