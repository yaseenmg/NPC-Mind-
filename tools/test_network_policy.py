import unittest

from network_policy import scan_java_source


class NetworkPolicyTest(unittest.TestCase):
    def assertForbidden(self, source):
        self.assertTrue(scan_java_source("Fixture.java", source), source)

    def assertAllowed(self, source):
        self.assertEqual([], scan_java_source("Fixture.java", source), source)

    def test_rejects_common_java_network_imports(self):
        for source in (
            "import java.net.URL; class X { URL u; }",
            "import java.net.*; class X {}",
            "import java.net.Socket; class X { Socket s; }",
            "import java.net.DatagramSocket; class X {}",
            "import java.net.http.HttpClient; class X {}",
            "import java.net.http.WebSocket; class X {}",
            "class X { java.net.ServerSocket s; }",
            "class X { java.net.InetAddress address; }",
        ):
            with self.subTest(source=source):
                self.assertForbidden(source)

    def test_rejects_popular_client_namespaces_and_uri_to_url(self):
        for source in (
            "import okhttp3.OkHttpClient; class X {}",
            "import org.apache.hc.client5.http.classic.HttpClient; class X {}",
            "import io.netty.channel.socket.SocketChannel; class X {}",
            "import io.ktor.client.HttpClient; class X {}",
            "class X { void f(java.net.URI uri) { uri.toURL(); } }",
        ):
            with self.subTest(source=source):
                self.assertForbidden(source)

    def test_rejects_dynamic_loading_and_reflective_invocation(self):
        self.assertForbidden("class X { Object x = Class.forName(name); }")
        self.assertForbidden("class X { void f(ClassLoader loader) { loader.loadClass(name); } }")
        self.assertForbidden("class X { void f() { ServiceLoader.load(Plugin.class); } }")
        self.assertForbidden("import java.lang.reflect.Method; class X { void f(Method m) throws Exception { m.invoke(null); } }")

    def test_ignores_comments_literals_and_non_network_uri_parsing(self):
        self.assertAllowed('''
            // import java.net.URL; URL.openConnection()
            /* new java.net.Socket() */
            class X {
                String sample = "new URL(\"https://example.invalid\")";
                java.net.URI parse(String value) { return java.net.URI.create(value); }
                String URL = "ordinary identifier";
            }
        ''')

    def test_reports_source_line(self):
        violations = scan_java_source("Fixture.java", "class X {}\nimport java.net.Socket;\n")
        self.assertTrue(any(":2:" in violation for violation in violations), violations)


if __name__ == "__main__":
    unittest.main()
