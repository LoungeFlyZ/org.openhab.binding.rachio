/**
 * Copyright (c) 2010-2018 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.rachio.internal.api;

import static org.openhab.binding.rachio.RachioBindingConstants.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.http.HttpService;
import org.osgi.service.http.NamespaceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main OSGi service and HTTP servlet for Rachio Image Loader
 *
 * @author Markus Michels (markus7017) - Initial distribution
 */
@Component(service = HttpServlet.class, configurationPolicy = ConfigurationPolicy.OPTIONAL, immediate = true)
public class RachioImageServlet extends HttpServlet {
    private static final long serialVersionUID = 8706067059503685993L;
    private final Logger logger = LoggerFactory.getLogger(RachioImageServlet.class);

    private HttpService httpService;

    /**
     * OSGi activation callback.
     *
     * @param config Service config.
     */
    @Activate
    protected void activate(Map<String, Object> config) {
        try {
            httpService.registerServlet(SERVLET_IMAGE_PATH, this, null, httpService.createDefaultHttpContext());
            logger.info("Started RachioImage servlet at {}", SERVLET_IMAGE_PATH);
        } catch (ServletException | NamespaceException e) {
            logger.warn("Could not start RachioImage servlet: {}", e.getMessage(), e);
        }
    }

    /**
     * OSGi deactivation callback.
     */
    @Deactivate
    protected void deactivate() {
        httpService.unregister(SERVLET_IMAGE_PATH);
        logger.info("RachioImage: Servlet stopped");
    }

    
    @Override
    protected void service(HttpServletRequest request, HttpServletResponse resp) throws ServletException, IOException {

        InputStream reader = null;
        OutputStream writer = null;
        try {
            String ipAddress = request.getHeader("HTTP_X_FORWARDED_FOR");
            if (ipAddress == null) {
                ipAddress = request.getRemoteAddr();
            }
            String path = request.getRequestURI().substring(0, SERVLET_IMAGE_PATH.length());
            logger.trace("RachioImage: Reqeust from {}:{}{} ({}:{}, {})", ipAddress, request.getRemotePort(), path,
                    request.getRemoteHost(), request.getServerPort(), request.getProtocol());
            if (!request.getMethod().equalsIgnoreCase(HTTP_METHOD_GET)) {
                logger.warn("RachioImage: Unexpected method='{}'", request.getMethod());
            }
            if (!path.equalsIgnoreCase(SERVLET_IMAGE_PATH)) {
                logger.warn("RachioImage: Invalid request received - path = {}", path);
                return;
            }

            String uri = request.getRequestURI().substring(request.getRequestURI().lastIndexOf("/") + 1);
            String imageUrl = SERVLET_IMAGE_URL_BASE + uri;
            logger.debug("RachioImage: {} image '{}' from '{}'", request.getMethod(), uri, imageUrl);
            setHeaders(resp);
            URL url = new URL(imageUrl);
            URLConnection conn = url.openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            reader = conn.getInputStream();
            writer = resp.getOutputStream();

            // read data in 4k chunks
            byte[] data = new byte[4096];
            int n;
            while (((n = reader.read(data)) != -1)) {
                writer.write(data, 0, n);
            }

        } catch (Exception e) {
            logger.warn("RachioImage: Unable to process request: {}", e.getMessage());
        } finally {
            if (writer != null) {
                writer.flush();
                writer.close();
            }
            if (reader != null) {
                reader.close();
            }
        }
    } // service()

    private void setHeaders(HttpServletResponse response) {
        response.setContentType(SERVLET_IMAGE_MIME_TYPE);
        response.setHeader("Access-Control-Allow-Origin", "*");
        // response.setHeader("Access-Control-Allow-Methods", "GET");
        // response.setHeader("Access-Control-Allow-Headers", "Origin, X-Requested-With, Content-Type, Accept");
    }

    @Reference
    public void setHttpService(HttpService httpService) {
        this.httpService = httpService;
    }

    public void unsetHttpService(HttpService httpService) {
        this.httpService = null;
    }

} // RachioImageServlet
