package org.jivesoftware.smack;

import java.io.IOException;
import java.util.LinkedList;

import org.jivesoftware.smack.util.ObservableReader;
import org.jivesoftware.smack.util.ObservableWriter;
import org.w3c.dom.Element;

public abstract class XMPPStream
{
    /**
     * Perform service discovery for this transport, returning a transport-specific
     * ConnectData containing the results.
     * <p>
     * Service discovery can be cancelled asynchronously by a call to {@link XMPPStream#disconnect}.
     * @return {@link ConnectData}
     * @throws XMPPException if service discovery fails or is cancelled
     */
    public abstract ConnectData getConnectData() throws XMPPException;

    /**
     * Retrieve the default connection data.  This should be called if getConnectData times
     * out.
     * <p>
     * This is separated from getConnectData in order to allow defaults to be retrieved
     * when service discovery is timing out.  This call will never fail.
     */
    public abstract ConnectData getDefaultConnectData();

    /**
     * Begin establishing the connection.  Returns after the connection has been
     * established, or throws XMPPException.
     * <p>
     * {@code attempt} must be <= {@link ConnectData#connectionAttempts}, and indicates
     * the discovered server to attempt.
     * <p>
     * After a successful call, {@link #setPacketCallbacks} must be called.
     * 
     * @param connectData a {@link ConnectData} instance returned by {@link #getConnectData}
     * @throws XMPPException if an error occurs during connection
     */
    public abstract void initializeConnection(ConnectData connectData, int attempt) throws XMPPException;
    
    /**
     * Set the {@link PacketCallback} to receive packets.  Until this is called, received
     * packets will be buffered.
     * <p>
     * This must not be called until {@link #initializeConnection} returns successfully, and
     * must only be called once.
     * <p>
     * Either a &lt;features/&gt; packet or an error callback is guaranteed to be made immediately
     * upon calling setPacketCallbacks, without waiting for I/O.
     */
    public abstract void setPacketCallbacks(PacketCallback callbacks);

    /**
     * Send the given packets to the server asynchronously.  This function may
     * block.  If the connection has already been closed, throws XMPPException. 
     */
    public abstract void writePacket(String packet) throws XMPPException;

    /**
     * Permanently close the connection, flushing any pending messages and cleanly
     * disconnecting the session.  If non-null and the connection is not already
     * closed, the given packets will be sent in the disconnection message.  This
     * function may block.
     */
    public abstract void gracefulDisconnect(String packet);

    /**
     * Forcibly disconnect the connection.  Future calls to readPacket will return
     * null.  If another thread is currently blocking in readPacket, it will return
     * null immediately. 
     */
    public abstract void disconnect();
    
    /**
     * Indicate to the stream that a stream reset has occurred.
     * @throws IOException
     */
    public abstract void streamReset() throws XMPPException;
    
    static abstract class PacketCallback {
        /** onPacket is called when a packet is received from the server. */
        public abstract void onPacket(Element packet);

        /** onError is called when the connection has been lost. */
        public abstract void onError(XMPPException error);

        /** onRecoverableError is called when the connection is lost, but can be
         *  recovered.  errorCount is the number of consecutive recoverable errors,
         *  increasing after each failed call to reconnect(). */
        public abstract void onRecoverableError(XMPPException error, int errorCount);
    };

    /**
     * Attempt to recover the connection.  This is called after {@link PacketCallback#onRecoverableError}
     * is received.
     * <p>
     * If the connection is already established, or if a recovery attempt is already in progress,
     * does nothing.
     * <p>
     * Throws XMPPException if the connection can not be recovered: if onRecoverableError has not
     * been called, or if {@link disconnect} has been called.
     * <p>
     * Otherwise, the recovery process begins asynchronously and the connection is considered
     * reconnected.  If connection recovery fails, onRecoverableError will be called again
     * with the errorCount parameter increased by one.
     * <p>
     *  
     * need to set the connection status back to CONNECTED; receiving a packet isn't explicit enough
     */
    public abstract void recoverConnection() throws XMPPException;

    /**
     * Returns the current connection ID, or null if the connection hasn't
     * established an ID yet.
     * 
     * @return the connection ID or null.
     */
    public abstract String getConnectionID();

    /**
     * Set the read and write events for this connection, which may be observed to monitor
     * incoming and outgoing data.  This must be called before {@link #initializeConnection()}.
     */
    public abstract void setReadWriteEvents(ObservableReader.ReadEvent readEvent, ObservableWriter.WriteEvent writeEvent);

    /**
     * Returns true if the connection to the server is secure.
     *
     * @return true if the connection to the server has successfully negotiated TLS.
     */
    public abstract boolean isSecureConnection();

    /**
     * @return true if the connection to the server is compressed.
     */
    public abstract boolean isUsingCompression();

    /** Opaque subclasses of ConnectData are returned by each transport. */
    static abstract public class ConnectData {
        /** Return the number of servers available for connection attempts. */
        abstract int connectionAttempts();
    };

};
