/**
 * Copyright 2016 Emmanuel Keller / QWAZR
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
package com.qwazr.jdbc.cache;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Driver implements java.sql.Driver {

    final static Logger LOGGER = Logger.getLogger(Driver.class.getPackage().getName());

    public final static String URL_PREFIX = "jdbc:cache:file:";
    public final static String CACHE_DRIVER_URL = "cache.driver.url";
    public final static String CACHE_DRIVER_CLASS = "cache.driver.class";
    public final static String CACHE_DRIVER_ACTIVE = "cache.driver.active";

    static {
        try {
            DriverManager.registerDriver(new Driver());
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
        }
    }

    private final ConcurrentHashMap<Path, ResultSetCacheImpl> resultSetCacheMap = new ConcurrentHashMap<>();

    public Connection connect(String url, Properties info) throws SQLException {

        if (!acceptsURL(url))
            return null;

        // Determine the optional backend connection
        final String cacheDriverUrl = info.getProperty(CACHE_DRIVER_URL);
        final String cacheDriverClass = info.getProperty(CACHE_DRIVER_CLASS);
        try {
            if (cacheDriverClass != null && !cacheDriverClass.isEmpty())
                Class.forName(cacheDriverClass);
        } catch (ClassNotFoundException e) {
            throw new SQLException("Cannot initialize the driver: " + cacheDriverClass, e);
        }

        final String cacheDriverActive = info.getProperty(CACHE_DRIVER_ACTIVE);
        final boolean active = cacheDriverActive == null ? true : Boolean.parseBoolean(cacheDriverActive);

        final Connection backendConnection = cacheDriverUrl == null || cacheDriverUrl.isEmpty() ?
                null :
                DriverManager.getConnection(cacheDriverUrl, info);

        if (url.length() <= URL_PREFIX.length())
            throw new SQLException("The path is empty: " + url);

        if (!active)
            return new CachedConnection(backendConnection, null);

        // Check the cache directory
        final Path cacheDirectory = FileSystems.getDefault().getPath(url.substring(URL_PREFIX.length()));

        final ResultSetCacheImpl resultSetCache;

        try {
            resultSetCache = resultSetCacheMap.computeIfAbsent(cacheDirectory, ResultSetCacheImpl::new);
        } catch (CacheException e) {
            throw e.getSQLException();
        }

        return new CachedConnection(backendConnection, resultSetCache);
    }

    public boolean acceptsURL(String url) throws SQLException {
        return url != null && url.startsWith(URL_PREFIX);
    }

    public DriverPropertyInfo[] getPropertyInfo(final String url, final Properties info) throws SQLException {
        return new DriverPropertyInfo[] { new DriverPropertyInfo(CACHE_DRIVER_URL, null),
                new DriverPropertyInfo(CACHE_DRIVER_CLASS, null), new DriverPropertyInfo(CACHE_DRIVER_ACTIVE, null) };
    }

    public int getMajorVersion() {
        return 1;
    }

    public int getMinorVersion() {
        return 2;
    }

    public boolean jdbcCompliant() {
        return false;
    }

    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return LOGGER;
    }

    public static ResultSetCache getCache(final Connection connection) throws SQLException {
        if (!(connection instanceof CachedConnection))
            throw new SQLException("The connection is not a cached connection");
        return ((CachedConnection) connection).getResultSetCache();
    }

}
