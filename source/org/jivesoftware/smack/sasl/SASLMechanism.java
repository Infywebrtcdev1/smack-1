/**
 * $RCSfile$
 * $Revision: $
 * $Date: $
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

package org.jivesoftware.smack.sasl;

import org.jivesoftware.smack.XMPPException;

import java.io.IOException;
import java.util.Map;
import java.util.HashMap;
import org.apache.harmony.javax.security.auth.callback.CallbackHandler;
import org.apache.harmony.javax.security.auth.callback.UnsupportedCallbackException;
import org.apache.harmony.javax.security.auth.callback.Callback;
import org.apache.harmony.javax.security.auth.callback.NameCallback;
import org.apache.harmony.javax.security.auth.callback.PasswordCallback;
import org.apache.harmony.javax.security.sasl.RealmCallback;
import org.apache.harmony.javax.security.sasl.RealmChoiceCallback;
import org.apache.harmony.javax.security.sasl.Sasl;
import org.apache.harmony.javax.security.sasl.SaslClient;
import org.apache.harmony.javax.security.sasl.SaslException;

/**
 * Base class for SASL mechanisms. Subclasses must implement these methods:
 * <ul>
 *  <li>{@link #getName()} -- returns the common name of the SASL mechanism.</li>
 * </ul>
 * Subclasses will likely want to implement their own versions of these mthods:
 *  <li>{@link #authenticate(String, String, String)} -- Initiate authentication stanza using the
 *  deprecated method.</li>
 *  <li>{@link #authenticate(String, String, CallbackHandler)} -- Initiate authentication stanza
 *  using the CallbackHandler method.</li>
 *  <li>{@link #challengeReceived(String)} -- Handle a challenge from the server.</li>
 * </ul>
 *
 * @author Jay Kline
 */
public class SASLMechanism extends SASLMechanismType {
    static public class Factory extends SASLMechanismType.Factory {
        public Factory(String name) { super(name); }
        public SASLMechanismType create() { return new SASLMechanism(name); }
    }

    protected SaslClient sc;
    protected String authenticationId;
    protected String password;

    public SASLMechanism(String mechanismName) {
        super(mechanismName);
    }

    /**
     * If a derived mechanism needs to set additional properties to pass to
     * {@link Sasl#createSaslClient}, override this method.
     */
    protected void applyProperties(Map<String,String> props) { }

    /**
     * Builds and sends the <tt>auth</tt> stanza to the server. The callback handler will handle
     * any additional information, such as the authentication ID or realm, if it is needed.
     *
     * @param username the username of the user being authenticated.
     * @param host     the hostname where the user account resides.
     * @param cbh      the CallbackHandler to obtain user information.
     * @throws XMPPException If a protocol error occurs or the user is not authenticated.
     * @throws MechanismNotSupported If this mechanism is not supported by the client.
     */
    public byte[] authenticate(String username, String host, CallbackHandler cbh)
    throws XMPPException, MechanismNotSupported
    {
        String[] mechanisms = { getName() };
        Map<String,String> props = new HashMap<String,String>();
        applyProperties(props);
        try {
            sc = Sasl.createSaslClient(mechanisms, username, "xmpp", host, props, cbh);
        } catch(IOException e) {
            throw new XMPPException(e);
        }
        if(sc == null)
            throw new MechanismNotSupported();
        return authenticate();
    }

    protected byte[] authenticate() throws XMPPException {
        if(!sc.hasInitialResponse())
            return null;

        try {
            return sc.evaluateChallenge(new byte[0]);
        } catch (SaslException e) {
            throw new XMPPException("SASL authentication failed", e);
        }
    }


    /**
     * The server is challenging the SASL mechanism for the stanza he just sent. Send a
     * response to the server's challenge.
     *
     * @param challenge the decoded challenge.
     * @throws IOException if an exception sending the response occurs.
     */
    public byte[] challengeReceived(byte[] challenge) throws XMPPException {
        try {
            return sc.evaluateChallenge(challenge);
        } catch(SaslException e) {
            throw new XMPPException(e);
        }
    }

    /**
     * 
     */
    CallbackHandler callbackHandler = new CallbackHandler() {
        public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
            for (int i = 0; i < callbacks.length; i++) {
                if (callbacks[i] instanceof NameCallback) {
                    NameCallback ncb = (NameCallback)callbacks[i];
                    ncb.setName(authenticationId);
                } else if(callbacks[i] instanceof PasswordCallback) {
                    PasswordCallback pcb = (PasswordCallback)callbacks[i];
                    pcb.setPassword(password.toCharArray());
                } else if(callbacks[i] instanceof RealmCallback) {
                    // Use the default realm provided by the server.
                    RealmCallback rcb = (RealmCallback)callbacks[i];
                    rcb.setText(rcb.getDefaultText());
                } else if(callbacks[i] instanceof RealmChoiceCallback){
                    //unused
                    //RealmChoiceCallback rccb = (RealmChoiceCallback)callbacks[i];
                } else {
                   throw new UnsupportedCallbackException(callbacks[i]);
                }
            }
        }
    };
}
