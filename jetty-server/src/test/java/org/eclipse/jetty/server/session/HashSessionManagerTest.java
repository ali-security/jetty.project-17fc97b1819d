//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.server.session;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionBindingListener;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.StdErrLog;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class HashSessionManagerTest
{
    @Test
    public void testDangerousSessionIdRemoval() throws Exception
    {
        final HashSessionManager manager = new HashSessionManager();
        manager.setDeleteUnrestorableSessions(true);
        manager.setLazyLoad(true);
        File testDir = MavenTestingUtils.getTargetTestingDir("hashes");
        testDir.mkdirs();
        manager.setStoreDirectory(testDir);

        MavenTestingUtils.getTargetFile("dangerFile.session").createNewFile();
        
        Assert.assertTrue("File should exist!", MavenTestingUtils.getTargetFile("dangerFile.session").exists());

        manager.getSession("../../dangerFile.session");
        
        Assert.assertTrue("File should exist!", MavenTestingUtils.getTargetFile("dangerFile.session").exists());

    }
    
   @Test
    public void testValidSessionIdRemoval() throws Exception
    {
        final HashSessionManager manager = new HashSessionManager();
        manager.setDeleteUnrestorableSessions(true);
        manager.setLazyLoad(true);
        File testDir = MavenTestingUtils.getTargetTestingDir("hashes");
        FS.ensureEmpty(testDir);
        
        manager.setStoreDirectory(testDir);

        Assert.assertTrue(new File(testDir, "validFile.session").createNewFile());
        
        Assert.assertTrue("File should exist!", new File(testDir, "validFile.session").exists());
       
        manager.getSession("validFile.session");

        Assert.assertTrue("File shouldn't exist!", !new File(testDir,"validFile.session").exists());
    }
    
    @Test
    public void testHashSession() throws Exception
    {
        File testDir = MavenTestingUtils.getTargetTestingDir("saved");
        IO.delete(testDir);
        testDir.mkdirs();
        
        Server server = new Server();
        SessionHandler handler = new SessionHandler();
        handler.setServer(server);
        HashSessionManager manager = new HashSessionManager();
        manager.setStoreDirectory(testDir);
        manager.setMaxInactiveInterval(5);
        Assert.assertTrue(testDir.exists());
        Assert.assertTrue(testDir.canWrite());
        handler.setSessionManager(manager);
        
        AbstractSessionIdManager idManager = new HashSessionIdManager();
        idManager.setWorkerName("foo");
        manager.setSessionIdManager(idManager);
        server.setSessionIdManager(idManager);
        
        server.start();
        manager.start();
        
        HashedSession session = (HashedSession)manager.newHttpSession(new Request(null, null));
        String sessionId = session.getId();
        
        session.setAttribute("one", new Integer(1));
        session.setAttribute("two", new Integer(2));    
        
        //stop will persist sessions
        manager.setMaxInactiveInterval(30); // change max inactive interval for *new* sessions
        manager.stop();
        
        Assert.assertTrue("File should exist!", new File(testDir, session.getId()).exists());
        
        //start will restore sessions
        manager.start();
        
        HashedSession restoredSession = (HashedSession)manager.getSession(sessionId);
        Assert.assertNotNull(restoredSession);
        
        Object o = restoredSession.getAttribute("one");
        Assert.assertNotNull(o);
        
        Assert.assertEquals(1, ((Integer)o).intValue());
        Assert.assertEquals(5, restoredSession.getMaxInactiveInterval());

        server.stop();
    }

    @Test
    public void testInvalidateWithThrowingDestroyListener() throws Exception
    {
        Server server = new Server();
        HashSessionManager manager = newStartedManager(server, null, newIdManager());
        try
        {
            final List<String> destroyed = new ArrayList<>();
            manager.addEventListener(new RecordingSessionListener(new ArrayList<String>(), destroyed));
            // destroy listeners are called in reverse order, so the throwing listener is called first
            manager.addEventListener(new ThrowingSessionListener(false, true));

            HashedSession session = (HashedSession)manager.newHttpSession(new Request(null, null));
            String id = session.getClusterId();
            AtomicBoolean unbound = new AtomicBoolean();
            session.setAttribute("secret", new UnbindTracker(unbound));

            session.invalidate();

            Assert.assertFalse("Session should be invalid", session.isValid());
            Assert.assertTrue("Attributes should be cleared", session.getAttributeMap().isEmpty());
            Assert.assertTrue("Attribute should be unbound", unbound.get());
            Assert.assertNull("Session should be removed from manager", manager.getSession(id));
            Assert.assertFalse("Session id should not be in use", manager.getSessionIdManager().idInUse(id));
            Assert.assertTrue("Other listeners should be notified", destroyed.contains(id));
        }
        finally
        {
            manager.stop();
            server.stop();
        }
    }

    @Test
    public void testTimeoutWithThrowingDestroyListener() throws Exception
    {
        Server server = new Server();
        HashSessionManager manager = newStartedManager(server, null, newIdManager());
        try
        {
            final List<String> destroyed = new ArrayList<>();
            manager.addEventListener(new RecordingSessionListener(new ArrayList<String>(), destroyed));
            manager.addEventListener(new ThrowingSessionListener(false, true));

            HashedSession session = (HashedSession)manager.newHttpSession(new Request(null, null));
            String id = session.getClusterId();
            AtomicBoolean unbound = new AtomicBoolean();
            session.setAttribute("secret", new UnbindTracker(unbound));
            // release the request reference held since creation so that expiry invalidates immediately
            session.complete();

            session.timeout();

            Assert.assertFalse("Session should be invalid", session.isValid());
            Assert.assertTrue("Attributes should be cleared", session.getAttributeMap().isEmpty());
            Assert.assertTrue("Attribute should be unbound", unbound.get());
            Assert.assertNull("Session should be removed from manager", manager.getSession(id));
            Assert.assertTrue("Other listeners should be notified", destroyed.contains(id));
        }
        finally
        {
            manager.stop();
            server.stop();
        }
    }

    @Test
    public void testInvalidateWithThrowingDestroyListenerRemovesStoredSession() throws Exception
    {
        File testDir = MavenTestingUtils.getTargetTestingDir("throwing-listener");
        IO.delete(testDir);
        testDir.mkdirs();

        Server server = new Server();
        HashSessionManager manager = newStartedManager(server, testDir, newIdManager());
        try
        {
            manager.addEventListener(new ThrowingSessionListener(false, true));

            HashedSession session = (HashedSession)manager.newHttpSession(new Request(null, null));
            session.setAttribute("one", new Integer(1));
            session.save(true);

            File sessionFile = new File(manager.getStoreDirectory(), session.getId());
            Assert.assertTrue("File should exist!", sessionFile.exists());

            session.invalidate();

            Assert.assertFalse("Session should be invalid", session.isValid());
            Assert.assertFalse("File shouldn't exist!", sessionFile.exists());
        }
        finally
        {
            manager.stop();
            server.stop();
        }
    }

    @Test
    public void testInvalidateAllWithThrowingDestroyListenerInOtherContext() throws Exception
    {
        Server server = new Server();
        HashSessionIdManager idManager = newIdManager();
        HashSessionManager managerA = newStartedManager(server, null, idManager);
        HashSessionManager managerB = newStartedManager(server, null, idManager);
        try
        {
            final List<String> destroyedB = new ArrayList<>();
            managerA.addEventListener(new ThrowingSessionListener(false, true));
            managerB.addEventListener(new RecordingSessionListener(new ArrayList<String>(), destroyedB));

            HashedSession sessionA = (HashedSession)managerA.newHttpSession(new Request(null, null));
            String id = sessionA.getClusterId();
            long now = System.currentTimeMillis();
            AbstractSession sessionB = managerB.newSession(now, now, id);
            managerB.addSession(sessionB, true);
            AtomicBoolean unboundA = new AtomicBoolean();
            sessionA.setAttribute("secret", new UnbindTracker(unboundA));

            // invalidating in context B invalidates the session with the same id in context A
            sessionB.invalidate();

            Assert.assertFalse("Session in context A should be invalid", sessionA.isValid());
            Assert.assertTrue("Attribute in context A should be unbound", unboundA.get());
            Assert.assertNull(managerA.getSession(id));
            Assert.assertFalse("Session in context B should be invalid", sessionB.isValid());
            Assert.assertNull(managerB.getSession(id));
            Assert.assertTrue("Listener in context B should be notified", destroyedB.contains(id));
            Assert.assertFalse("Session id should not be in use", idManager.idInUse(id));
        }
        finally
        {
            managerA.stop();
            managerB.stop();
            server.stop();
        }
    }

    @Test
    public void testThrowingCreateListener() throws Exception
    {
        Server server = new Server();
        HashSessionManager manager = newStartedManager(server, null, newIdManager());
        try
        {
            final List<String> created = new ArrayList<>();
            // create listeners are called in order, so the throwing listener is called first
            manager.addEventListener(new ThrowingSessionListener(true, false));
            manager.addEventListener(new RecordingSessionListener(created, new ArrayList<String>()));

            HttpSession session = manager.newHttpSession(new Request(null, null));

            Assert.assertNotNull(session);
            String id = ((AbstractSession)session).getClusterId();
            Assert.assertTrue("Session should be valid", ((AbstractSession)session).isValid());
            Assert.assertSame(session, manager.getSession(id));
            Assert.assertTrue("Other listeners should be notified", created.contains(id));
        }
        finally
        {
            manager.stop();
            server.stop();
        }
    }

    private HashSessionManager newStartedManager(Server server, File storeDir, HashSessionIdManager idManager) throws Exception
    {
        SessionHandler handler = new SessionHandler();
        handler.setServer(server);
        HashSessionManager manager = new HashSessionManager();
        if (storeDir != null)
            manager.setStoreDirectory(storeDir);
        manager.setMaxInactiveInterval(30);
        handler.setSessionManager(manager);

        manager.setSessionIdManager(idManager);
        server.setSessionIdManager(idManager);

        server.start();
        manager.start();
        return manager;
    }

    private HashSessionIdManager newIdManager()
    {
        HashSessionIdManager idManager = new HashSessionIdManager();
        idManager.setWorkerName("foo");
        return idManager;
    }

    private static class ThrowingSessionListener implements HttpSessionListener
    {
        private final boolean _throwOnCreate;
        private final boolean _throwOnDestroy;

        ThrowingSessionListener(boolean throwOnCreate, boolean throwOnDestroy)
        {
            _throwOnCreate = throwOnCreate;
            _throwOnDestroy = throwOnDestroy;
        }

        @Override
        public void sessionCreated(HttpSessionEvent se)
        {
            if (_throwOnCreate)
                throw new RuntimeException("Intentional exception in sessionCreated");
        }

        @Override
        public void sessionDestroyed(HttpSessionEvent se)
        {
            if (_throwOnDestroy)
                throw new RuntimeException("Intentional exception in sessionDestroyed");
        }
    }

    private static class RecordingSessionListener implements HttpSessionListener
    {
        private final List<String> _created;
        private final List<String> _destroyed;

        RecordingSessionListener(List<String> created, List<String> destroyed)
        {
            _created = created;
            _destroyed = destroyed;
        }

        @Override
        public void sessionCreated(HttpSessionEvent se)
        {
            _created.add(se.getSession().getId());
        }

        @Override
        public void sessionDestroyed(HttpSessionEvent se)
        {
            _destroyed.add(se.getSession().getId());
        }
    }

    private static class UnbindTracker implements HttpSessionBindingListener
    {
        private final AtomicBoolean _unbound;

        UnbindTracker(AtomicBoolean unbound)
        {
            _unbound = unbound;
        }

        @Override
        public void valueBound(HttpSessionBindingEvent event)
        {
        }

        @Override
        public void valueUnbound(HttpSessionBindingEvent event)
        {
            _unbound.set(true);
        }
    }
}
