/**
 * Copyright (C) 2015 Red Hat, Inc. (jbrazdil@redhat.com)
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.pnc.causeway.pncclient;

import org.jboss.pnc.causeway.CausewayException;
import org.jboss.pnc.causeway.config.CausewayConfig;
import org.jboss.pnc.causeway.pncclient.BuildArtifacts.PncArtifact;
import org.jboss.pnc.client.BuildClient;
import org.jboss.pnc.client.ClientException;
import org.jboss.pnc.client.ProductMilestoneClient;
import org.jboss.pnc.client.RemoteCollection;
import org.jboss.pnc.client.RemoteResourceException;
import org.jboss.pnc.client.RemoteResourceNotFoundException;
import org.jboss.pnc.constants.Attributes;
import org.jboss.pnc.dto.Artifact;
import org.jboss.pnc.dto.Build;
import org.jboss.pnc.dto.ProductMilestone;
import org.jboss.pnc.enums.BuildStatus;
import org.jboss.pnc.rest.api.parameters.BuildsFilterParameters;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.core.Response;
import java.io.InputStream;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Scanner;

import lombok.extern.slf4j.Slf4j;
import static org.jboss.pnc.rest.configuration.SwaggerConstants.NOT_FOUND_CODE;

/**
 * Created by jdcasey on 2/9/16.
 */
@ApplicationScoped
@Deprecated
@Slf4j
public class PncClientImpl implements PncClient {

    private final ProductMilestoneClient milestoneClient;
    private final BuildClient buildClient;

    @Inject
    public PncClientImpl(CausewayConfig config) {
        this.milestoneClient = new ProductMilestoneClient(config.getPncClientConfig());
        this.buildClient = new BuildClient(config.getPncClientConfig());
    }

    @Override
    public String getTagForMilestone(int milestoneId) throws CausewayException {
        ProductMilestone milestone;
        try {
            milestone = milestoneClient.getSpecific(String.valueOf(milestoneId));
        } catch (RemoteResourceNotFoundException e) {
            throw new CausewayException(
                    "Can not read tag because PNC haven't managed to find product milestone with id " + milestoneId
                            + " - response " + e.getStatus(),
                    e);
        } catch (RemoteResourceException e) {
            throw new CausewayException(
                    "Can not read tag because PNC responded with an error when getting product milestone " + milestoneId
                            + " - response " + e.getStatus(),
                    e);
        } catch (ClientException e) {
            throw new CausewayException("Unknown error - message = " + e.getMessage(), e);
        }
        return milestone.getProductVersion().getAttributes().get(Attributes.BREW_TAG_PREFIX);
    }

    @Override
    public Collection<Build> findBuildsOfProductMilestone(int milestoneId) throws CausewayException {
        Collection<Build> builds = new HashSet<>();
        try {
            RemoteCollection<Build> buildPages = milestoneClient.getBuilds(
                    String.valueOf(milestoneId),
                    new BuildsFilterParameters(),
                    Optional.empty(),
                    Optional.of("status==SUCCESS"));
            for (Build build : buildPages) {
                if (build.getStatus().equals(BuildStatus.SUCCESS))
                    ;
                builds.add(build);
            }
        } catch (RemoteResourceException e) {
            throw new CausewayException(
                    "Can not read builds for product milestone " + milestoneId + " - response " + e.getStatus(),
                    e);
        }
        return builds;
    }

    @Override
    public String getBuildLog(String buildId) throws CausewayException {
        Optional<InputStream> log;
        try {
            log = buildClient.getBuildLogs(String.valueOf(buildId));
            InputStream logInput = log.orElseThrow(
                    () -> new CausewayException(
                            "Build log for Build " + buildId + " is empty - response " + NOT_FOUND_CODE));
            Scanner s = new Scanner(logInput).useDelimiter("\\A");
            return s.hasNext() ? s.next() : "";
        } catch (RemoteResourceException e) {
            throw new CausewayException(
                    "Can not read build log of build " + buildId + " because PNC responded with an error - response "
                            + e.getStatus(),
                    e);
        }
    }

    @Override
    public InputStream getSources(String id) throws CausewayException {
        try {
            Response response = buildClient.getInternalScmArchiveLink(id);
            try {
                if (response.getStatus() >= 400) {
                    log.warn(
                            "Got status " + response.getStatus() + " from sources endpoint. Message: "
                                    + response.readEntity(String.class));
                    throw new CausewayException(
                            "Can not read sources of build " + id + ", received status " + response.getStatus());
                }
                return response.readEntity(InputStream.class);
            } catch (RuntimeException e) {
                response.close();
                throw new CausewayException("Can not read sources of build " + id + ": " + e.getMessage(), e);
            }
        } catch (RemoteResourceException e) {
            throw new CausewayException(
                    "Can not read sources of build " + id + " because PNC responded with an error - response "
                            + e.getStatus(),
                    e);
        }
    }

    @Override
    public BuildArtifacts findBuildArtifacts(String buildId) throws CausewayException {
        Collection<PncArtifact> builtArtifacts = getArtifacts(buildId, buildClient::getBuiltArtifacts);
        Collection<PncArtifact> dependantArtifact = getArtifacts(buildId, buildClient::getDependencyArtifacts);

        BuildArtifacts build = new BuildArtifacts();

        build.buildArtifacts.addAll(builtArtifacts);
        build.dependencies.addAll(dependantArtifact);

        return build;
    }

    private PncArtifact toPncArtifact(Artifact artifact) {

        String deployPath = artifact.getDeployPath();
        if (deployPath.startsWith("/"))
            deployPath = deployPath.substring(1);
        return new PncArtifact(
                artifact.getId(),
                artifact.getIdentifier(),
                deployPath,
                artifact.getMd5(),
                artifact.getDeployUrl(),
                artifact.getSize() == null ? 1 : artifact.getSize(),
                artifact.getArtifactQuality());
    }

    private Collection<PncArtifact> getArtifacts(
            String buildId,
            IntFunctionWithRemoteException<RemoteCollection<Artifact>> query) throws CausewayException {
        Collection<PncArtifact> pncArtifacts = new HashSet<>();
        try {
            RemoteCollection<Artifact> artifacts = query.get(buildId);
            for (Artifact artifact : artifacts) {
                pncArtifacts.add(toPncArtifact(artifact));
            }
        } catch (RemoteResourceException e) {
            throw new CausewayException(
                    "Can't get info for build with id " + buildId + " - response " + e.getStatus(),
                    e);
        }
        return pncArtifacts;
    }

    /**
     * Special IntFunction that throws {@code org.jboss.pnc.client.RemoteResourceException}
     *
     * It was created so that method {@code getArtifacts} could catch and handle the exception, otherwise it would have
     * to be handled in higher level method.
     *
     * @param <T> Return type of the Function
     */
    @FunctionalInterface
    public interface IntFunctionWithRemoteException<T> {
        T get(String i) throws RemoteResourceException;
    }

}
