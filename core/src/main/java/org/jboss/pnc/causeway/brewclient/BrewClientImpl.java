/**
 * Copyright (C) 2015 Red Hat, Inc. (jbrazdil@redhat.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.pnc.causeway.brewclient;

import com.redhat.red.build.koji.KojiClient;
import com.redhat.red.build.koji.KojiClientException;
import com.redhat.red.build.koji.KojijiErrorInfo;
import com.redhat.red.build.koji.model.KojiImportResult;
import com.redhat.red.build.koji.model.json.KojiImport;
import com.redhat.red.build.koji.model.json.KojiJsonConstants;
import com.redhat.red.build.koji.model.xmlrpc.KojiBuildInfo;
import com.redhat.red.build.koji.model.xmlrpc.KojiNVR;
import com.redhat.red.build.koji.model.xmlrpc.KojiSessionInfo;
import org.jboss.pnc.causeway.CausewayException;
import org.jboss.pnc.causeway.CausewayFailure;
import org.jboss.pnc.causeway.config.CausewayConfig;
import org.jboss.pnc.causeway.rest.BrewBuild;
import org.jboss.pnc.causeway.rest.BrewNVR;
import org.jboss.pnc.causeway.rest.pnc.BuildImportResultRest;
import org.jboss.pnc.causeway.rest.pnc.BuildImportStatus;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import com.redhat.red.build.koji.model.xmlrpc.KojiTagInfo;

@ApplicationScoped
@Slf4j
public class BrewClientImpl implements BrewClient {

    private static final String KOJI_COMMUNICATION_FAILURE = "Failure while communicating with Koji: ";

    public static final String BUILD_TAG_SUFIX = "-candidate";

    private final KojiClient koji;

    private final String brewUrl;

    @Inject
    public BrewClientImpl(KojiClient koji, CausewayConfig config) {
        this.koji = koji;
        brewUrl = config.getKojiWebURL();
    }

    @Override
    public BrewBuild findBrewBuildOfNVR(BrewNVR nvr) throws CausewayException {
        try {
            KojiSessionInfo session = login();

            KojiNVR knvr = new KojiNVR(nvr.getKojiName(), nvr.getVersion(), nvr.getRelease());
            KojiBuildInfo bi = koji.getBuildInfo(knvr, session); // returns null if missing

            koji.logout(session);
            if (bi == null) {
                return null;
            }
            checkPNCImportedBuild(bi);
            return toBrewBuild(bi, nvr);
        } catch (KojiClientException ex) {
            throw new CausewayException(KOJI_COMMUNICATION_FAILURE + ex.getMessage(), ex);
        }
    }

    @Override
    public BrewBuild findBrewBuild(int id) throws CausewayException {
        KojiBuildInfo buildInfo;

        KojiSessionInfo session = login();
        try {
            buildInfo = koji.getBuildInfo(id, session);
        } catch (KojiClientException ex) {
            throw new CausewayException(KOJI_COMMUNICATION_FAILURE + ex.getMessage(), ex);
        }
        koji.logout(session);

        if (buildInfo == null) {
            return null;
        }
        checkPNCImportedBuild(buildInfo);
        return toBrewBuild(buildInfo);
    }

    /**
     * Checks if the brew build is imported by PNC. If not, throws an exception.
     *
     * @throws CausewayException when the brew build is not imporeted by PNC.
     */
    private void checkPNCImportedBuild(KojiBuildInfo bi) throws CausewayException {
        final Map<String, Object> extra = bi.getExtra();
        Object buildSystem = extra == null ? null : extra.get(KojiJsonConstants.BUILD_SYSTEM);
        if (buildSystem == null || !BuildTranslatorImpl.PNC.equals(buildSystem)) {
            throw new CausewayFailure(
                    "Found conflicting brew build " + bi.getId() + " (build doesn't have "
                            + KojiJsonConstants.BUILD_SYSTEM + " set to " + BuildTranslatorImpl.PNC + ")");
        }
    }

    private static BrewBuild toBrewBuild(KojiBuildInfo bi, BrewNVR nvr) throws CausewayException {
        return new BrewBuild(bi.getId(), nvr);
    }

    private static BrewBuild toBrewBuild(KojiBuildInfo bi) throws CausewayException {
        return new BrewBuild(bi.getId(), new BrewNVR(bi.getName(), bi.getVersion(), bi.getRelease()));
    }

    @Override
    public void tagBuild(String tag, BrewBuild build) throws CausewayException {
        log.info("Applying tag {} on build {}.", tag, build.getNVR());
        KojiSessionInfo session = login();
        try {
            koji.addPackageToTag(tag, build.getKojiName(), session);
            koji.tagBuild(tag + BUILD_TAG_SUFIX, build.getNVR(), session);
        } catch (KojiClientException ex) {
            String msg = KOJI_COMMUNICATION_FAILURE;
            if (ex.getMessage().contains("policy violation")) {
                String userName = session.getUserInfo().getUserName();
                msg += "This is most probably because of missing permisions. Ask RCM to add " + "permisions for user '"
                        + userName + "' to add packages to tag '" + tag + "' and to tag builds into tag '" + tag
                        + BUILD_TAG_SUFIX + "'. Cause: ";
            }
            throw new CausewayFailure(msg + ex.getMessage(), ex);
        }
        koji.logout(session);
    }

    @Override
    public boolean isBuildTagged(String tag, BrewBuild build) throws CausewayException {
        KojiSessionInfo session = login();
        String tagName = tag + BUILD_TAG_SUFIX;
        try {
            List<KojiTagInfo> tags = koji.listTags(build.getId(), session);
            return tags.stream().map(KojiTagInfo::getName).anyMatch(n -> tagName.equals(n));
        } catch (KojiClientException ex) {
            throw new CausewayException("Failure while getting tag information from build: " + ex.getMessage(), ex);
        } finally {
            koji.logout(session);
        }
    }

    @Override
    public void untagBuild(String tag, BrewNVR nvr) throws CausewayException {
        log.info("Removing tag {} from build {}.", tag, nvr.getNVR());
        KojiSessionInfo session = login();
        try {
            koji.untagBuild(tag + BUILD_TAG_SUFIX, nvr.getNVR(), session);
        } catch (KojiClientException ex) {
            throw new CausewayFailure(KOJI_COMMUNICATION_FAILURE + ex.getMessage(), ex);
        }
        koji.logout(session);
    }

    @Override
    public BuildImportResultRest importBuild(
            BrewNVR nvr,
            String buildRecordId,
            KojiImport kojiImport,
            ImportFileGenerator importFiles) throws CausewayException {
        log.info("Importing build {}.", nvr.getNVR());
        BuildImportResultRest ret = new BuildImportResultRest();
        ret.setBuildRecordId(buildRecordId);
        ret.setStatus(BuildImportStatus.SUCCESSFUL);
        try {
            KojiSessionInfo session = login();

            KojiImportResult result = koji.importBuild(kojiImport, importFiles, session);
            koji.logout(session);

            if (checkImportErrors(result, importFiles)) {
                ret.setStatus(BuildImportStatus.FAILED);
            }

            KojiBuildInfo bi = result.getBuildInfo();

            if (bi == null) {
                ret.setErrorMessage("Import to koji failed");
                ret.setStatus(BuildImportStatus.ERROR);
            } else {
                ret.setBrewBuildId(bi.getId());
                ret.setBrewBuildUrl(getBuildUrl(bi.getId()));
            }

            log.info("Build {} import status: {}.", nvr.getNVR(), ret.getStatus());
            return ret;
        } catch (KojiClientException ex) {
            throw new CausewayException(KOJI_COMMUNICATION_FAILURE + ex.getMessage(), ex);
        }
    }

    @Override
    public BrewBuild importBuild(BrewNVR nvr, KojiImport kojiImport, ImportFileGenerator importFiles)
            throws CausewayException {
        KojiSessionInfo session = login();
        KojiImportResult result;
        try {
            result = koji.importBuild(kojiImport, importFiles, session);
        } catch (KojiClientException ex) {
            checkImportErrors(null, importFiles); // to ensure errors are logged for users
            throw new CausewayFailure("Failure while importing builds to Koji: " + ex.getMessage(), ex);
        }
        koji.logout(session);

        if (checkImportErrors(result, importFiles)) {
            throw new CausewayFailure("Failure while importing artifacts");
        }

        KojiBuildInfo bi = result.getBuildInfo();

        if (bi == null) {
            throw new CausewayException("Import to koji failed for unknown reson. No build data.");
        }
        return toBrewBuild(bi, nvr);
    }

    private boolean checkImportErrors(KojiImportResult result, ImportFileGenerator importFiles) {
        boolean errorsPresent = false;
        Map<String, KojijiErrorInfo> kojiErrors = result == null ? null : result.getUploadErrors();
        if (kojiErrors != null) {
            for (Map.Entry<String, KojijiErrorInfo> e : kojiErrors.entrySet()) {
                String artifactId = importFiles.getId(e.getKey());
                if (log.isWarnEnabled()) {
                    KojijiErrorInfo errorInfo = e.getValue();
                    String message = String
                            .format("Failed to import artifact %s (%s): %s", artifactId, e.getKey(), errorInfo);
                    log.warn(message, errorInfo.getError());
                }
                errorsPresent = true;
            }
        }
        return errorsPresent;
    }

    @Override
    public String getBuildUrl(int id) {
        return brewUrl + id;
    }

    @Override
    public boolean tagsExists(String tag) throws CausewayException {
        boolean packageTag, buildTag;
        try {
            KojiSessionInfo session = login();

            packageTag = koji.getTag(tag, session) != null;
            buildTag = koji.getTag(tag + BUILD_TAG_SUFIX, session) != null;

            koji.logout(session);
        } catch (KojiClientException ex) {
            throw new CausewayException(KOJI_COMMUNICATION_FAILURE + ex.getMessage(), ex);
        }
        return packageTag && buildTag;
    }

    private KojiSessionInfo login() throws CausewayException {
        try {
            return koji.login();
        } catch (KojiClientException ex) {
            throw new CausewayException("Failure while loging to Koji: " + ex.getMessage(), ex);
        }
    }

}
