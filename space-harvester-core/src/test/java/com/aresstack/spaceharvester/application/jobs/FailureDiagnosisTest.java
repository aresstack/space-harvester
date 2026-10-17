package com.aresstack.spaceharvester.application.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import java.net.ConnectException;
import java.security.cert.CertPathValidatorException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

class FailureDiagnosisTest {

    @Test
    void httpStatusComesFromTheChain() {
        SourceAccessException failure = new SourceAccessException("wiki", SourceAccessException.Reason.ACCESS_DENIED,
                "Abruf abgelehnt", new IllegalStateException("Confluence antwortete mit Fehler (HTTP 403)"));

        FailureDiagnosis.Findings findings = FailureDiagnosis.of(failure, "SOURCE_ACCESS_DENIED");

        assertThat(findings.httpStatus()).isEqualTo(403);
        assertThat(findings.category()).isEqualTo(JobEvent.Category.ACCESS);
        assertThat(findings.network()).isFalse();
        assertThat(findings.detail()).contains("SourceAccessException", "HTTP 403");
    }

    @Test
    void theRouteOfTheFailedRequestIsTakenFromTheChain() {
        java.net.UnknownHostException unknown = new java.net.UnknownHostException("confluence.atlassian.com");
        RequestRoute.attach(unknown, "PROXY proxy.corp:8080", "WebClient");
        SourceAccessException failure = new SourceAccessException("wiki", SourceAccessException.Reason.UNAVAILABLE,
                "nicht erreichbar", unknown);

        FailureDiagnosis.Findings findings = FailureDiagnosis.of(failure, "SOURCE_UNAVAILABLE");

        assertThat(findings.route()).isEqualTo("PROXY proxy.corp:8080 · WebClient");
        assertThat(findings.category()).isEqualTo(JobEvent.Category.NETWORK);
        assertThat(FailureDiagnosis.of(new ConnectException("x"), null).route()).isNull();
    }

    @Test
    void certificateProblemsAreTlsFindings() {
        SSLHandshakeException handshake = new SSLHandshakeException("PKIX path validation failed");
        handshake.initCause(new CertPathValidatorException("certificate has expired"));
        SourceAccessException failure = new SourceAccessException("wiki", SourceAccessException.Reason.UNAVAILABLE,
                "nicht erreichbar", handshake);

        FailureDiagnosis.Findings findings = FailureDiagnosis.of(failure, "SOURCE_UNAVAILABLE");

        assertThat(findings.category()).isEqualTo(JobEvent.Category.NETWORK);
        assertThat(findings.network()).isTrue();
        assertThat(findings.tlsFinding()).isEqualTo("SSLHandshakeException: PKIX path validation failed");
    }

    @Test
    void connectionFailuresAreNetwork() {
        FailureDiagnosis.Findings findings = FailureDiagnosis.of(
                new RuntimeException("Abruf", new ConnectException("Connection refused")), "UNEXPECTED");

        assertThat(findings.category()).isEqualTo(JobEvent.Category.NETWORK);
        assertThat(findings.tlsFinding()).isNull();
    }

    @Test
    void storageCodesAndTheComponentAreRecognised() {
        FailureDiagnosis.Findings findings = FailureDiagnosis.of(
                new JobFailedException("EXPORT_STORAGE_FULL", "Ablage voll"), "EXPORT_STORAGE_FULL");

        assertThat(findings.category()).isEqualTo(JobEvent.Category.STORAGE);
        assertThat(findings.component()).isEqualTo("FailureDiagnosisTest");
    }
}
