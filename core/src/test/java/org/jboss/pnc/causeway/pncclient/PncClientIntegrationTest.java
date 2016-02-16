package org.jboss.pnc.causeway.pncclient;

import static org.junit.Assert.assertEquals;

import org.jboss.pnc.rest.restmodel.ProductReleaseRest;
import org.jboss.pnc.rest.restmodel.response.Singleton;
import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.jboss.resteasy.client.jaxrs.ResteasyWebTarget;
import org.junit.Before;
import org.junit.Test;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.Response;

public class PncClientIntegrationTest {
    private ResteasyClient client;
    private String pncUrl;
    private Integer productReleaseId;

    @Before
    public void setUp() throws Exception {
        client = new ResteasyClientBuilder().build();
        pncUrl = "http://ncl-nightly.stage.engineering.redhat.com/pnc-rest/rest";
        productReleaseId = 1;
    }

    @Test
    public void testReadProductRelease() throws Exception {
        ResteasyWebTarget target = client.target(pncUrl + "/product-releases/" + productReleaseId);
        Response response = target.request().get();

        Singleton<ProductReleaseRest> responseEntity = response.readEntity(new GenericType<Singleton<ProductReleaseRest>>() {});

        assertEquals(productReleaseId, responseEntity.getContent().getId());
        response.close();
    }

    @Test
    public void testReadProductReleaseUsingProxy() throws Exception {
        ResteasyWebTarget target = client.target(pncUrl);
        ProductReleaseEndpoint endpoint = target.proxy(ProductReleaseEndpoint.class);

        Singleton<ProductReleaseRest> responseEntity = endpoint.getEntity(productReleaseId);

        assertEquals(productReleaseId, responseEntity.getContent().getId());
    }


    @Path("/product-releases")
    public interface ProductReleaseEndpoint {

        @GET
        @Path("/{id}")
        public Singleton<ProductReleaseRest> getEntity(@PathParam("id") int productReleaseId);
    }
}