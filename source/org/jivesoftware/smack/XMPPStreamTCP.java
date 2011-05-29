/**
 * $RCSfile$
 * $Revision: 11616 $
 * $Date: 2010-02-09 07:40:11 -0500 (Tue, 09 Feb 2010) $
 *
 * Copyright 2003-2007 Jive Software.
 *
 * All rights reserved. Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jivesoftware.smack;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.LinkedBlockingQueue;

import javax.net.ssl.SSLSocket;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.jivesoftware.smack.packet.XMPPError;
import org.jivesoftware.smack.util.DNSUtil;
import org.jivesoftware.smack.util.ObservableReader;
import org.jivesoftware.smack.util.ObservableWriter;
import org.jivesoftware.smack.util.ThreadUtil;
import org.jivesoftware.smack.util.WriterListener;
import org.jivesoftware.smack.util.XmlUtil;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import com.kenai.jbosh.AbstractBody;

/**
 * XMPP TCP transport, implementing TLS and compression. 
 */
public class XMPPStreamTCP extends XMPPStream
{
    private Socket socket = null; 
    private Reader reader = null;
    private Writer writer = null;
    
    private ObservableReader.ReadEvent readEvent;
    private ObservableWriter.WriteEvent writeEvent;

    private ObservableWriter.WriteEvent keepaliveMonitorWriteEvent;

    /** True if the connection is encrypted, whether or not the certificate is verified. */
    private boolean usingTLS = false;

    /** True if the connection is secure (encrypted with a verified certificate). */
    private boolean usingSecureConnection = false;

    /* True if XMPP compression is enabled.  If TLS compression is enabled, this is false. */
    private boolean usingXMPPCompression = false;

    /* True if TLS compression is enabled. */
    private boolean usingTLSCompression = false;

    private ConnectionConfiguration config;
    private String originalServiceName;

    private XmlPullParser parser;
    XMPPSSLSocketFactory sslSocketFactory;

    /** If true, the most recent <features/> advertised <starttls/>. */
    private boolean featureStartTLSReceived = false;
    
    /** The compression methods advertised in the most recent <features/>. */
    private List<String> featureCompressionMethods;

    /** If true, readPacket received a null entry, and all future calls to readPacket will
     *  return null. */
    private boolean hitEndOfStream = false;

    public void writePacket(String packet) throws IOException {
        // writer can be cleared by calls to disconnect.  We can't hold a lock
        // on XMPPStreamTCP while we use it, since it can block indefinitely.
        // Take a reference to writer.
        Writer writerCopy;
        synchronized(this) {
            writerCopy = this.writer;
        }

        if(writerCopy == null)
            throw new IOException("Wrote a packet while the connection was closed");

        synchronized(writerCopy) {
            writerCopy.write(packet);
            writerCopy.flush();
        }
    }
    public boolean isSecureConnection() { return usingSecureConnection; }
    public boolean isUsingCompression() { return usingXMPPCompression || usingTLSCompression; }

    String connectionID;
    public String getConnectionID() { return connectionID; }

    // When this changes, XMPPStreamTCP must be signalled.
    private QueuedMessage nextReadEvent = null;

    private PacketReaderThread packetReaderThread;
    private Thread threadUnjoined = null;

    public XMPPStreamTCP(ConnectionConfiguration config)
    {
        this.config = config;
        connectionClosed = true;
        
        try {
            parser = XmlPullParserFactory.newInstance().newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        }
        catch (XmlPullParserException xppe) {
            xppe.printStackTrace();
            throw new RuntimeException(xppe);
        }

        /* We update config.serviceName when we see the service name from the server,
         * but we need to retain the original value for TLS certificate checking. */
        originalServiceName = config.getServiceName();
        keepaliveMonitorWriteEvent = new ObservableWriter.WriteEvent();
    }

    private int discoveryIndex;
    public void setDiscoveryIndex(int index) {
        discoveryIndex = index;
    }

    public void setReadWriteEvents(ObservableReader.ReadEvent readEvent, ObservableWriter.WriteEvent writeEvent) {
        this.writeEvent = writeEvent;
        this.readEvent = readEvent;
    }

    /**
     * Begin the initial connection to the server.  Returns when the connection
     * is established.
     */
    public synchronized void initializeConnection() throws XMPPException {
        if(socket != null)
            throw new RuntimeException("The connection has already been initialized");

        String host = config.getHost();
        int port = config.getPort();

        // If no host was specified, look up the XMPP service name.
        // XXX: This should be cancellable.
        if(host == null) {
            // This will return the same results each time, because the weight
            // shuffling is cached.
            DNSUtil.XMPPDomainLookup lookup = new DNSUtil.XMPPDomainLookup(config.getServiceName(), true);
            Vector<DNSUtil.HostAddress> addresses = lookup.run();
            if(discoveryIndex >= addresses.size())
                throw new XMPPException("No more servers to attempt (tried all " + addresses.size() + ")",
                        XMPPError.Condition.remote_server_not_found);
            host = addresses.get(discoveryIndex).getHost();
            port = addresses.get(discoveryIndex).getPort();
        } else {
            // If we're not autodiscovering servers, we have only one server to try.
            if(discoveryIndex > 0)
                throw new XMPPException("No more servers to attempt", XMPPError.Condition.remote_server_not_found);
        }

        try {
            socket = config.getSocketFactory().createSocket(host, port);
            
            initReaderAndWriter();
        } catch (UnknownHostException e) {
            throw new XMPPException("Could not connect to " + host + ":" + port, e);
        } catch(IOException e) {
            throw new XMPPException("Could not connect to " + host + ":" + port, e);
        }

        packetReaderThread = new PacketReaderThread();
        packetReaderThread.setName("XMPP packet reader (" + host + ":" + port + ")");
        packetReaderThread.start();

        // Mark the connection open.  Once we do this, other threads can call disconnect()
        // and gracefulDisconnect().
        connectionClosed = false;

        try {
            setupTransport();
        } catch(XMPPException e) {
            disconnect();
        }

        // After a successful connect, fill in config with the host we actually connected
        // to.  This allows the client to detect what it's actually talking to, and is necessary
        // for SASL authentication.  Don't do this until we're actually connected, so later
        // autodiscovery attempts aren't modified.
        config.setHost(host);
        config.setPort(port);

        /* Start keepalives after TLS has been set up. */
        startKeepAliveProcess();
    }

    private void setupTransport() throws XMPPException {
        /* Handle transport-level negotiation.  Read the <features/> packet to see if
         * we should set up TLS or compression, and repeat until we have nothing more
         * to negotiate. */
        while(true) {
            // Read the next packet, leaving it in the queue.  This serves two purposes:
            // first, if this packet is a <features/> that we're not interested in, and
            // therefore ends transport negotiation, we need to leave the packet in place,
            // so the next call to readPacket() will retrieve it.  Second, it prevents
            // the packet reader thread from moving on and trying to read the next packet,
            // which is necessary when we negotiate a new stream format; if we enable TLS
            // or compression, we can't begin reading the next packet until the new stream
            // format is set up by initReaderAndWriter.
            Element packet = peekPacket();

            // If packet is null, the stream terminated normally.  The only way the
            // stream can terminate normally here is if disconnect() was called asynchronously;
            // report that as an error, akin to InterruptedException.
            if(packet == null)
                throw new XMPPException("Disconnected by user");

            boolean consumePacket = true;
            try {
                if(processInitializationPacket(packet)) {
                    /* We're still initializing the connection, so don't return any packets. */
                    continue;
                }

                // Stream initialization is complete.  The packet we just read wasn't for us,
                // so don't take it.
                consumePacket = false;
                return;
            } catch(IOException e) {
                throw new XMPPException("I/O error establishing connection to server", e);
            } finally {
                if(consumePacket) {
                    // This packet was for us, so call readPacket() now to actually remove it.
                    Element actualPacket = readPacket();

                    // Sanity check: the packet we just consumed is the same packet that we
                    // received from peekPacket().
                    if(actualPacket != packet)
                        throw new AssertionError("readPacket didn't return the same result as peekPacket");
                }
            }
        }
    }

    /**
     * Return true if zlib (deflate) compression is supported.
     */
    private static boolean getZlibSupported() {
        try {
            Class.forName("com.jcraft.jzlib.ZOutputStream");
        }
        catch (ClassNotFoundException e) {
            // throw new IllegalStateException("Cannot use compression. Add smackx.jar to the classpath");
            return false;
        }
        return true;
    }


    /**
     * Attempt to negotiate a feature, based
     * 
     * @return true if a feature is being negotiated.
     * @throws XMPPException
     * @throws IOException
     */
    private boolean negotiateFeature() throws XMPPException, IOException {
        // If TLS is required but the server doesn't offer it, disconnect
        // from the server and throw an error. First check if we've already negotiated TLS
        // and are secure, however (features get parsed a second time after TLS is established).
        if (!isSecureConnection() && !featureStartTLSReceived &&
                config.getSecurityMode() == ConnectionConfiguration.SecurityMode.required)
        {
            throw new XMPPException("Server does not support security (TLS), " +
                    "but security required by connection configuration.",
                    XMPPError.Condition.forbidden);
        }

        if(!isSecureConnection() && featureStartTLSReceived &&
                config.getSecurityMode() != ConnectionConfiguration.SecurityMode.disabled) {
            
            // If we havn't yet set up sslSocketFactory, and encryption is available,
            // set it up. 
            if(sslSocketFactory == null)
                sslSocketFactory = new XMPPSSLSocketFactory(config, originalServiceName);

            if(sslSocketFactory.isAvailable()) {
                // The server is offering TLS, so enable it.
                startTLSReceived();

                /* Transport initialization is continuing; we should now receive <proceed/>. */
                return true;
            }
            
            // Encryption was offered, but we weren't able to initialize it.  If the user required
            // TLS, fail.  We could handle this failure earlier, but it's a rare case.
            if(!sslSocketFactory.isAvailable() && config.getSecurityMode() == ConnectionConfiguration.SecurityMode.required) {
                throw new XMPPException("System does not support encryption, " +
                        "but security is required by connection configuration.", XMPPError.Condition.forbidden);
            }
        }

        // Compression must be negotiated after encryption. 
        if(!usingXMPPCompression && config.isCompressionEnabled()) {
            // If we we share a supported compression method with the server, enable compression.
            if(getZlibSupported() && featureCompressionMethods.contains("zlib")) {
                // Only attempt to negotiate a protocol once per <features/>.  If it fails
                // we'll be notified by <failure/>, which will retry feature negotiation; if
                // we don't do this, it'll just try to negotiate the same compressor over and
                // over.
                featureCompressionMethods.remove("zlib");

                // gar: we need to tell the thread to reset
                enableCompressionMethod("zlib");

                /* Transport initialization is continuing; we should now receive <compressed/>. */
                return true;
            }
        }
        
        // We're not interested in the transport features of this connection, so
        // the transport negotiation is complete.  The <features/> we just received
        // must be returned to the application.
        return false;
    }

    /**
     * Process a packet during initialization.  Return true if the packet was
     * processed and we're still initializing.  Return false if initialization
     * is no longer taking place, and the packet should be returned to the user
     * via readPacket.
     * 
     * @param node The packet to process.
     * @return whether initialization continues.
     * @throws XMPPException
     * @throws IOException
     */
    private boolean processInitializationPacket(Element node)
        throws XMPPException, IOException
    {
        if (node.getNodeName().equals("features")) {
            featureStartTLSReceived = false;
            featureCompressionMethods = new ArrayList<String>();

            for (Element child: XmlUtil.getChildElements(node)) {
                if (!usingTLS && child.getNodeName().equals("starttls")) {
                    featureStartTLSReceived = true;

                    for (Element startTlsChild: XmlUtil.getChildElements(child)) {
                        if (startTlsChild.getNodeName().equals("required")) {
                            if (config.getSecurityMode() == ConnectionConfiguration.SecurityMode.disabled) {
                                throw new XMPPException(
                                    "TLS required by server but not allowed by connection configuration", XMPPError.Condition.forbidden);
                            }
                        }
                    }
                }
                else if (child.getNodeName().equals("compression")) {
                    for (Element compressionChild: XmlUtil.getChildElements(child)) {
                        if (!compressionChild.getNodeName().equals("method"))
                            continue;
                        featureCompressionMethods.add(XmlUtil.getTextContent(compressionChild));
                    }
                }
            }

            // We've received a new feature list; see if we can negotiate any of them. 
            return negotiateFeature();
        }

        else if(node.getNodeName().equals("proceed") && node.getNamespaceURI().equals("urn:ietf:params:xml:ns:xmpp-tls")) {
            // The server has acknowledged our <starttls/> request.  Enable TLS.
            try {
                proceedTLSReceived();
            } catch(Exception e) {
                e.printStackTrace();
                throw new XMPPException("Error initializing TLS", e);
            }

            return true;
        }
        else if(node.getNodeName().equals("failure")) { 
            if(node.getNamespaceURI().equals("urn:ietf:params:xml:ns:xmpp-tls")) {
                // The server offered STARTTLS, but responded with a failure when we
                // tried to use it.  The stream will be closed by the server.  This is an
                // abnormal condition.
                throw new XMPPException("Server failed while initializing TLS");
            } else if(node.getNamespaceURI().equals("http://jabber.org/protocol/compress")) {
                // The server offered compression, but failed when we tried to use it.
                // This isn't a fatal error, so attempt to negotiate the next feature.
                // XXX: test this (jabberd2 is always returning success, even on a bogus method)
                return negotiateFeature();
            }
        }
        else if (node.getNodeName().equals("compressed") && node.getNamespaceURI().equals("http://jabber.org/protocol/compress")) {
            // Server confirmed that it's possible to use stream compression. Start
            // stream compression
            usingXMPPCompression = true;
            
            // Reinitialize the reader and writer with compression enabled.
            initReaderAndWriter();
            
            /* Don't return this packet.  The stream is restarting, and the new stream:features
             * received after the stream restart will be the first packet returned. */
            return true;
        }

        /* We received an initialization packet we don't know about.  Is it valid for
         * the server to send anything at all before <features/>? */
        return true;
    }

    /**
     * TLS is supported by the server.  If encryption is enabled, start TLS.  
     */
    private void startTLSReceived() throws IOException {
        writer.write("<starttls xmlns=\"urn:ietf:params:xml:ns:xmpp-tls\"/>");
        writer.flush();
    }
    
    /**
     * Starts using stream compression that will compress network traffic. Traffic can be
     * reduced up to 90%. Therefore, stream compression is ideal when using a slow speed network
     * connection. However, the server and the client will need to use more CPU time in order to
     * un/compress network data so under high load the server performance might be affected.<p>
     * <p/>
     * Stream compression has to have been previously offered by the server. Currently only the
     * zlib method is supported by the client. Stream compression negotiation has to be done
     * before authentication took place.<p>
     * <p/>
     * Note: to use stream compression the smackx.jar file has to be present in the classpath.
     */
    private void enableCompressionMethod(String method) throws IOException {
        writer.write("<compress xmlns='http://jabber.org/protocol/compress'>");
        writer.write("<method>" + method + "</method></compress>");
        writer.flush();
    }

    /**
     * Parse the <stream version> string.  If not present, treat the
     * version as "0.9" as required by spec.  If the string fails to
     * parse, raises an exception.
     * 
     * @return the version number multiplied by 100; version 1.5 is 150 
     */
    private static int parseVersionString(String version) throws XMPPException {
        if(version == null)
            version = "0.9";

        // Verify the string all at once, so we don't have to do it below.
        if(!version.matches("\\d+(\\.\\d*)?"))
            throw new XMPPException("Invalid version string from server: " + version);

        String[] parts = version.split("\\.");
        int major = Integer.parseInt(parts[0]);
        int minor = Integer.parseInt(parts[1]);
        if(minor < 10)
            minor *= 10;

        return major * 100 + (minor % 100);
    }

    private synchronized void shutdown_stream() {
        try {
            // Closing the socket will cause any blocking readers and writers on the
            // socket to stop waiting and throw an exception.
            if(socket != null)
                socket.close();
        } catch(IOException e) {
            throw new RuntimeException("Unexpected I/O error disconnecting socket", e);
        }

        // Shut down the reader thread, if we're not running in it.  Otherwise, this will be
        // done when the owner thread calls disconnect().
        if(packetReaderThread != null) {
            if(packetReaderThread == Thread.currentThread())
                threadUnjoined = packetReaderThread;
            else
                ThreadUtil.uninterruptibleJoin(packetReaderThread);
        }

        // Shut down the keepalive thread, if any.  Do this after closing the socket,
        // so it'll receive an IOException immediately if it's currently blocking to write,
        // guaranteeing it'll exit quickly.
        if(this.keepAlive != null) {
            this.keepAlive.close();
            this.keepAlive = null;
        }

        this.socket = null;

        if (reader != null) {
            try { reader.close(); } catch (IOException ignore) { /* ignore */ }
            reader = null;
        }

        if (writer != null) {
            try { writer.close(); } catch (IOException ignore) { /* ignore */ }
            writer = null;
        }
    }

    /**
     * Forcibly disconnect the stream.  If readPacket() is waiting for input, it will
     * return end of stream immediately.
     */
    boolean connectionClosed = false;
    public void disconnect() {
        synchronized(this) {
            if(!connectionClosed) {
                // Queue a QueuedEnd; this guarantees that anyone waiting on a readPacket
                // call will stop.  If it's already a QueuedError, leave it alone.
                if(nextReadEvent == null || !(nextReadEvent  instanceof QueuedError)) {
                    nextReadEvent = new QueuedEnd();
                    // notifyAll happens below
                }

                connectionClosed = true;
                notifyAll();

                shutdown_stream();
            }

            // If packetReaderThread calls disconnect(), it'll shut everything down except for
            // itself, since a thread can't join itself.  Join the thread now if necessary.
            if(threadUnjoined != null && threadUnjoined != Thread.currentThread()) {
                Thread thread = threadUnjoined;
                threadUnjoined = null;

                ThreadUtil.uninterruptibleJoin(thread);
            }
        }
    }

    static boolean waitUntilTime(Object obj, long waitUntil) {
        long ms = waitUntil - System.currentTimeMillis();
        if(ms <= 0)
            return false;

        try {
            obj.wait(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return true;
    }

    static void waitUninterruptible(Object obj) {
        try {
            obj.wait();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void gracefulDisconnect(String packet)
    {
        /* Attempt to close the stream. */
        synchronized(this) {
            if(connectionClosed) {
                // If the connection is already closed or in the process of closing when
                // we're called again, close immediately.
                disconnect();
                return;
            }

            try {
                if (packet == null)
                    packet = "";

                // Append the final packet (if any) and </stream> and send them together,
                // so they're sent together.
                packet += "</stream:stream>";
                writePacket(packet);
            }
            catch (IOException e) {
                // If this fails for some reason, just close the connection.
                e.printStackTrace();
                disconnect();
                return;
            }

            // Wait for the connection to close gracefully.
            long waitUntil = System.currentTimeMillis() + SmackConfiguration.getPacketReplyTimeout();
            while(!connectionClosed) {
                if(!waitUntilTime(this, waitUntil))
                    break;
            }

            // If the connection didn't close gracefully, force it.
            disconnect();
        }
    }

    /* A queue of packets received from the server. */
    private static abstract interface QueuedMessage { };
    private static class QueuedResponse implements QueuedMessage {
        public Element body;
        QueuedResponse(Element body) { this.body = body; }
    };
    private static class QueuedError implements QueuedMessage {
        QueuedError(XMPPException error) { this.error = error; }
        XMPPException error;
    };
    private static class QueuedEnd implements QueuedMessage { };

    /**
     * Return the current read event.  If none is available, blocks.  The read
     * event will not be cleared; multiple consecutive calls to this function
     * will return the same value.
     */
    private synchronized Element peekPacket() throws XMPPException {
        if(hitEndOfStream)
            return null;

        while(nextReadEvent == null)
            waitUninterruptible(this);

        if(nextReadEvent instanceof QueuedEnd || nextReadEvent instanceof QueuedError) {
            // Set hitEndOfStream, so all future calls to readPacket will continue to return null.
            hitEndOfStream = true;
        }

        // QueuedEnd indicates that the stream has terminated normally.
        if(nextReadEvent instanceof QueuedEnd)
            return null;

        if(nextReadEvent instanceof QueuedError)
            throw ((QueuedError) nextReadEvent).error;
        else
            return ((QueuedResponse) nextReadEvent).body;
    }

    public synchronized Element readPacket() throws XMPPException {
        Element result = peekPacket();

        // Clear the event.
        nextReadEvent = null;
        notifyAll();

        return result;
    }

    /**
     * Read settings from the top-level stream header.
     * 
     * If a non-null Element is returned, the stream header is from a legacy
     * server, and the provided element should be processed as if it was a
     * received packet.
     */
    private Element loadStreamSettings(Element element) throws XMPPException {
        // Save the connection id.
        connectionID = element.getAttribute("id");

        // Save the service name.
        String from = element.getAttribute("from");
        if(from != null)
            config.setServiceName(from);

        // If the server isn't version 1.0, then we'll assume it doesn't support
        // <features/>.  Consider the connection established now.

        // The version attribute is only present for version 1.0 and higher.
        int protocolVersion = parseVersionString(element.getAttribute("version"));

        // If the protocol version is lower than 1.0, we may not receive <features/>.
        // Disable compression and encryption, and establish the session now.
        if(protocolVersion < 100) {
            // Old versions of the protocol may not send <features/>.  Mask this to the
            // user by returning a dummy <features/> node.
            try {
                DocumentBuilderFactory dbfac = DocumentBuilderFactory.newInstance();
                DocumentBuilder docBuilder = dbfac.newDocumentBuilder();
                Document doc = docBuilder.newDocument();
                return doc.createElementNS("http://etherx.jabber.org/streams", "features");
            } catch(ParserConfigurationException e) {
                throw new RuntimeException("Unexpected error", e);
            }
        }
        return null;
    }

    class PacketReaderThread extends Thread {
        public void run() {
            while(true) {
                try {
                    Element result = readPacketLoop(parser);

                    synchronized(XMPPStreamTCP.this) {
                        if(parser.getDepth() == 1) {
                            // Process the stream header.  If it returns a packet, treat it
                            // as the received packet; otherwise move on and read the next packet.
                            result = loadStreamSettings(result);
                            if(result == null)
                                continue;
                        }

                        QueuedResponse resp = new QueuedResponse(result);
                        nextReadEvent = resp;
                        XMPPStreamTCP.this.notifyAll();

                        // Wait for someone to take the message.
                        while(nextReadEvent == resp)
                            waitUninterruptible(XMPPStreamTCP.this);
                    }
                } catch(XMPPException e) {
                    synchronized(XMPPStreamTCP.this) {
                        // This is our normal exit path; the stream will be closed and readPacketLoop
                        // will throw a socket error.
                        nextReadEvent = new QueuedError(e);
                        XMPPStreamTCP.this.notifyAll();
                    }

                    disconnect();
                    return;
                }
            }
        }
    };

    /**
     * Read the next element from the given parser.
     * <p>
     * This function must run without locking XMPPStreamTCP, as it blocks.  In order to
     * prevent accidental use of data which requires locking, this function is static
     * and only uses the provided parser.
     * <p>
     * If the parser returns at depth 1, the returned Element represents the top-level
     * stream header.  Otherwise, the parser returns at depth 2 and the returned Element
     * represents a received XMPP stanza.
     */
    private static Element readPacketLoop(XmlPullParser parser) throws XMPPException {
        try {
            // Depth 0 means we're just starting; 1 means we've read the stream header;
            // 2 means we've read at least one packet.  If we're at depth 2, then the
            // previous element must be END_TAG, as a result of calling ReadNodeFromXmlPull
            // below.
            if (parser.getDepth() > 2)
                throw new RuntimeException("Unexpected parser depth: " + parser.getDepth());
            if (parser.getDepth() == 2 && parser.getEventType() != XmlPullParser.END_TAG)
                throw new RuntimeException("Unexpected event type: " + parser.getEventType());

            // Read the next packet.
            parser.next();

            /* If there are any text nodes between stanzas, ignore them. */
            while (parser.getEventType() == XmlPullParser.TEXT)
                parser.next();

            // END_DOCUMENT means the stream has ended.
            if (parser.getEventType() == XmlPullParser.END_DOCUMENT)
                throw new XMPPException("Session terminated");

            // If we receive END_TAG, then </stream:stream> has been closed and the
            // connection is about to be closed.  If we receive END_DOCUMENT, then the
            // stream has been closed abruptly.
            if (parser.getEventType() == XmlPullParser.END_TAG)
                throw new XMPPException("Session terminated");

            // We've checked all other possibilities; the event type must be START_TAG.
            if (parser.getEventType() != XmlPullParser.START_TAG)
                throw new RuntimeException("Unexpected state from XmlPullParser: " + parser.getEventType());

            // We must now be at depth 1 (<stream> starting) or 2 (a new stanza).
            if (parser.getDepth() != 1 && parser.getDepth() != 2)
                throw new RuntimeException("Unexpected post-packet parser depth: " + parser.getDepth());

            /* If we havn't yet received the opening <stream> tag, wait until we get it. */
            if(parser.getDepth() == 1) {
                // Check that the opening stream is what we expect.
                if (!parser.getName().equals("stream") ||
                        !parser.getNamespace().equals("http://etherx.jabber.org/streams") ||
                        !parser.getNamespace(null).equals("jabber:client")) {
                    throw new XMPPException("Expected stream:stream");
                }

                return XmlUtil.ReadElementFromXmlPullNonRecursive(parser);
            } else {
                // We have an XMPP stanza.  Read the whole thing into a DOM node and return it.
                return XmlUtil.ReadNodeFromXmlPull(parser);
            }
        }
        catch (XmlPullParserException e) {
            throw new XMPPException("XML error", e);
        }
        catch (IOException e) {
            throw new XMPPException("I/O error", e);
        }
    }

    /**
     * Resets the parser using the latest connection's reader. Reseting the parser is necessary
     * when the plain connection has been secured or when a new opening stream element is going
     * to be sent by the server.
     */
    private void resetParser()
    {
        /*
         * There are two distinct cases where we need a stream reset.
         *
         * During transport negotiation, when compression and/or encryption are enabled,
         * a stream reset is performed.  In this case, the input stream has changed, replaced
         * with compression/encryption wrappers.
         *
         * Later on, higher-level systems like SASL require a stream reset.  (This seems to
         * serve no purpose other than complicating clients.)  In this case, we don't need to
         * change streams, since the stream is the same.
         *
         * The important distinction is that when a transport-level reset happens and changes
         * the stream, readPacket is never being executed.  If it was, it would get confused:
         * the existing parser would continue to operate on the old stream.
         *
         * When a higher-level reset happens, readPacket is likely to be currently running,
         * so it's important that we not recreate the parser or change the stream.  We still
         * call setInput, in order to reset the stream state, but we're giving it the same
         * stream it already had.
         */
        try {
            parser.setInput(reader);
        }
        catch (XmlPullParserException xppe) {
            xppe.printStackTrace();
            throw new RuntimeException(xppe);
        }
    }

    /**
     * Sends to the server a new stream element. This operation may be requested several times
     * so we need to encapsulate the logic in one place. This message will be sent while doing
     * TLS, SASL and resource binding.
     *
     * @throws IOException If an error occurs while sending the stanza to the server.
     */
    private void openStream() throws IOException {
        StringBuilder stream = new StringBuilder();
        stream.append("<stream:stream");
        stream.append(" to=\"").append(config.getServiceName()).append("\"");
        stream.append(" xmlns=\"jabber:client\"");
        stream.append(" xmlns:stream=\"http://etherx.jabber.org/streams\"");
        stream.append(" version=\"1.0\">");
        writer.write(stream.toString());
        writer.flush();
    }

    private void initReaderAndWriter() throws XMPPException, IOException
    {
        InputStream inputStream = socket.getInputStream(); 
        OutputStream outputStream = socket.getOutputStream(); 

        if(usingXMPPCompression) {
            try {
                Class<?> zoClass = Class.forName("com.jcraft.jzlib.ZOutputStream");
                Constructor<?> constructor =
                    zoClass.getConstructor(OutputStream.class, Integer.TYPE);
                OutputStream compressed_out = (OutputStream) constructor.newInstance(outputStream, 9);
                Method method = zoClass.getMethod("setFlushMode", Integer.TYPE);
                method.invoke(compressed_out, 2); // Z_SYNC_FLUSH
    
                Class<?> ziClass = Class.forName("com.jcraft.jzlib.ZInputStream");
                constructor = ziClass.getConstructor(InputStream.class);
                InputStream compressed_in = (InputStream) constructor.newInstance(inputStream);
                method = ziClass.getMethod("setFlushMode", Integer.TYPE);
                method.invoke(compressed_in, 2); // Z_SYNC_FLUSH
    
                inputStream = compressed_in;
                outputStream = compressed_out;
            }
            catch (Exception e) {
                // If this fails, we can't continue; the other side is expecting
                // compression.  This shouldn't fail, since we checked importing the
                // class earlier.
                e.printStackTrace();
                throw new RuntimeException("Unexpected error initializing compression", e);
            }
        }        
        
        reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));
        writer = new BufferedWriter(new OutputStreamWriter(outputStream, "UTF-8"));
        
        /* Send incoming and outgoing data to the read and write observers, if any. */
        if(readEvent != null) {
            ObservableReader obsReader = new ObservableReader(reader);
            obsReader.setReadEvent(readEvent);
            reader = obsReader;
        }

        if(writeEvent != null) {
            ObservableWriter obsWriter = new ObservableWriter(writer);
            obsWriter.setWriteEvent(writeEvent);
            writer = obsWriter;
        }

        // For keepalive purposes, add an observer wrapper to monitor writes.
        {
            ObservableWriter obsWriter = new ObservableWriter(writer);
            obsWriter.setWriteEvent(keepaliveMonitorWriteEvent);
            writer = obsWriter;
        }
        
        streamReset();
    }

    private void proceedTLSReceived() throws Exception
    {
        // Secure the plain connection
        SSLSocket sslSocket = sslSocketFactory.attachSSLConnection(socket, originalServiceName, socket.getPort());
        sslSocket.setSoTimeout(0);
        
        // We have our own keepalive.  Don't enabling TCP keepalive, too.
        // sslSocket.setKeepAlive(true);
        
        // Proceed to do the handshake
        sslSocket.startHandshake();

        socket = sslSocket;
        
        // If usingSecureConnection is false, we're encrypted but we couldn't verify
        // the server's certificate.
        usingTLS = true;
        CertificateException insecureReason = sslSocketFactory.isInsecureConnection(sslSocket);
        usingSecureConnection = (insecureReason == null);

        // Record if TLS compression is active, so we won't try to negotiate XMPP
        // compression too.   
        if(sslSocketFactory.getCompressionMethod(sslSocket) != null)
            usingTLSCompression = true;

        // Initialize the reader and writer with the new secured version
        initReaderAndWriter();
        
        // If !usingSecureConnection, then we have an encrypted TLS connection but with
        // an unvalidated certificate.  If a secure connection was required, fail.
        if(!usingSecureConnection && config.getSecurityMode() == ConnectionConfiguration.SecurityMode.required) {
            throw new XMPPException("Server does not support security (TLS), " + 
                    "but the configuration requires a secure connection.",
                    XMPPError.Condition.forbidden, insecureReason);
        }
    }

    /**
     * When authentication is successful, we must open a new <stream:stream>.
     * This is step 12 in http://xmpp.org/extensions/xep-0178.html#c2s. 
     */
    public void streamReset() throws XMPPException
    {
        /* The <stream:stream> element will not be closed after a stream reset,
         * so reset the parser state. */
        resetParser();

        /* Send the stream:stream to start the new stream. */
        try {
            openStream();
        } catch(IOException e) {
            throw new XMPPException("Error resetting stream", e);
        }
    }

    /**
     * Starts the keepalive process. A white space (aka heartbeat) is going to be
     * sent to the server every 30 seconds (by default) since the last stanza was sent
     * to the server.
     */
    private KeepAliveTask keepAlive;
    private void startKeepAliveProcess() {
        // Schedule a keep-alive task to run if the feature is enabled. will write
        // out a space character each time it runs to keep the TCP/IP connection open.
        int keepAliveInterval = SmackConfiguration.getKeepAliveInterval();
        if (keepAliveInterval == 0)
            return;
        
        KeepAliveTask task = new KeepAliveTask(keepAliveInterval);
        keepAlive = task;
    }

    /**
     * A TimerTask that keeps connections to the server alive by sending a space
     * character on an interval.
     */
    private class KeepAliveTask implements Runnable {
        private int delay;
        private Thread thread;
        private boolean done = false;

        public KeepAliveTask(int delay) {
            this.delay = delay;
            
            Thread keepAliveThread = new Thread(this);
            keepAliveThread.setDaemon(true);
            keepAliveThread.setName("Smack Keepalive");
            keepAliveThread.start();
            this.thread = keepAliveThread;
        }

        /**
         * Close the keepalive thread. 
         */
        public void close() {
            synchronized (this) {
                done = true;
                thread.interrupt();
            }

            ThreadUtil.uninterruptibleJoin(thread);
        }

        long lastActive = System.currentTimeMillis();
        public void run() {
            // Add a write listener to track the time of the most recent write.  This is used
            // to only send heartbeats when the connection is idle.
            long lastActive[] = {System.currentTimeMillis()};
            WriterListener listener = new WriterListener() {
                public void write(String str) {
                    KeepAliveTask.this.lastActive = System.currentTimeMillis();
                }
            };
            keepaliveMonitorWriteEvent.addWriterListener(listener);
            
            while (true) {
                synchronized (writer) {
                    // Send heartbeat if no packet has been sent to the server for a given time
                    if (System.currentTimeMillis() - KeepAliveTask.this.lastActive >= delay) {
                        try {
                            writer.write(" ");
                            writer.flush();
                        }
                        catch (IOException e) {
                            // Do nothing, and assume that whatever caused an error
                            // here will cause one in the main code path, too.  This
                            // will also happen if the write blocked and XMPPStreamTCP.disconnect
                            // closed the socket.
                        }
                    }
                }

                try {
                    synchronized (this) {
                        if (done)
                            break;
                        
                        // Sleep until we should write the next keep-alive.
                        wait(delay);
                    }
                }
                catch (InterruptedException ie) {
                    // close() interrupted us to shut down the thread.
                    break;
                }
            }

            keepaliveMonitorWriteEvent.removeWriterListener(listener);
        }
    }
};