package org.ruoyi.aiintegration.web;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B1 传输契约：HTTP 网关的 {@code deliverFrame} 只接受带
 * {@code : ai-delivery <permit> <operation>} 的数据帧；裸 id/event/data 帧必须
 * fail-closed（{@code unprotected stream frame}）。本地传输使用同一行级规则。
 */
@Tag("dev")
class F20CodingSseTransportContractTest {

    private static final String PROTECTED_FRAME =
            ": ai-delivery permit-1 op-1\n"
                    + "id:1\n"
                    + "event:run.started\n"
                    + "data:{\"type\":\"run.started\"}\n\n";

    private static final String BARE_FRAME =
            "id:1\nevent:run.started\ndata:{\"type\":\"run.started\"}\n\n";

    @Test
    void protectedCodingFrameIsAcceptedAndMetadataIsStripped() throws Exception {
        try (AckServer server = new AckServer()) {
            AiGatewayClient client = new AiGatewayClient(2_000);
            MockHttpServletResponse response = new MockHttpServletResponse();

            client.deliverFrame(PROTECTED_FRAME.getBytes(StandardCharsets.UTF_8), response,
                    new AiGatewayClient.DeliveryAck(
                            URI.create("http://127.0.0.1:" + server.port() + "/ack"),
                            "T1", "platform:T1:42", "svc"));

            String body = response.getContentAsString();
            assertThat(body).contains("id:1", "event:run.started", "data:{\"type\":\"run.started\"}");
            assertThat(body).doesNotContain("ai-delivery");
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(server.awaitAcknowledged()).isTrue();
        }
    }

    @Test
    void bareCodingFrameIsRejectedBeforeAnyAcknowledgement() throws Exception {
        try (AckServer server = new AckServer()) {
            AiGatewayClient client = new AiGatewayClient(2_000);
            MockHttpServletResponse response = new MockHttpServletResponse();

            assertThatThrownBy(() -> client.deliverFrame(
                    BARE_FRAME.getBytes(StandardCharsets.UTF_8), response,
                    new AiGatewayClient.DeliveryAck(
                            URI.create("http://127.0.0.1:" + server.port() + "/ack"),
                            "T1", "platform:T1:42", "svc")))
                    .isInstanceOf(AiGatewayClient.UpstreamUnavailableException.class)
                    .hasMessageContaining("unprotected stream frame");
            assertThat(server.awaitAcknowledged()).isFalse();
        }
    }

    /** Minimal loopback HTTP server returning 204 to the delivery acknowledgement. */
    private static final class AckServer implements AutoCloseable {
        private final ServerSocket socket;
        private final Thread thread;
        private final java.util.concurrent.atomic.AtomicBoolean acknowledged =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        private AckServer() throws Exception {
            socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            thread = new Thread(this::serve, "f20a-ack-server");
            thread.setDaemon(true);
            thread.start();
        }

        private int port() {
            return socket.getLocalPort();
        }

        private boolean awaitAcknowledged() throws InterruptedException {
            thread.join(1_500);
            return acknowledged.get();
        }

        private void serve() {
            try (Socket client = socket.accept()) {
                InputStream input = client.getInputStream();
                java.io.ByteArrayOutputStream headers = new java.io.ByteArrayOutputStream();
                int current;
                while ((current = input.read()) >= 0) {
                    headers.write(current);
                    byte[] bytes = headers.toByteArray();
                    int length = bytes.length;
                    if (length >= 4 && bytes[length - 4] == '\r' && bytes[length - 3] == '\n'
                            && bytes[length - 2] == '\r' && bytes[length - 1] == '\n') {
                        break;
                    }
                }
                String headerText = headers.toString(StandardCharsets.ISO_8859_1);
                int contentLength = 0;
                for (String line : headerText.split("\r\n")) {
                    if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                        contentLength = Integer.parseInt(line.substring(15).strip());
                    }
                }
                for (int index = 0; index < contentLength; index++) {
                    input.read();
                }
                acknowledged.set(true);
                OutputStream output = client.getOutputStream();
                output.write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
                output.flush();
            } catch (Exception ignored) {
                // The client aborted; the test assertion reports the observable outcome.
            }
        }

        @Override
        public void close() throws Exception {
            socket.close();
            thread.interrupt();
        }
    }
}
