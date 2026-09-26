package com.example.MigrosBackend.config.security;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.util.OnCommittedResponseWrapper;

import static org.assertj.core.api.Assertions.assertThat;

class OnCommittedResponseWrapperContentLengthTest {

    @Test
    void setIntHeaderContentLength_isTrackedSoResponseCommitsBeforeBodyWrite() throws Exception {
        TrackingWrapper wrapper = new TrackingWrapper(new MockHttpServletResponse());

        wrapper.setIntHeader("Content-Length", "1234".length());
        wrapper.getWriter().write("1234");

        assertThat(wrapper.committed).isTrue();
    }

    @Test
    void setHeaderContentLength_isTrackedSoResponseCommitsBeforeBodyWrite() throws Exception {
        TrackingWrapper wrapper = new TrackingWrapper(new MockHttpServletResponse());

        wrapper.setHeader("Content-Length", String.valueOf("1234".length()));
        wrapper.getWriter().write("1234");

        assertThat(wrapper.committed).isTrue();
    }

    @Test
    void addIntHeaderContentLength_isTrackedSoResponseCommitsBeforeBodyWrite() throws Exception {
        TrackingWrapper wrapper = new TrackingWrapper(new MockHttpServletResponse());

        wrapper.addIntHeader("Content-Length", "1234".length());
        wrapper.getWriter().write("1234");

        assertThat(wrapper.committed).isTrue();
    }

    private static final class TrackingWrapper extends OnCommittedResponseWrapper {

        private boolean committed;

        private TrackingWrapper(HttpServletResponse response) {
            super(response);
        }

        @Override
        protected void onResponseCommitted() {
            this.committed = true;
        }
    }
}
