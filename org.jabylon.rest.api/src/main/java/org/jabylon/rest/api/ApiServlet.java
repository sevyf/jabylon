/**
 * (C) Copyright 2013 Jabylon (http://www.jabylon.org) and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.jabylon.rest.api;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.core.internal.preferences.Base64;
import org.eclipse.emf.cdo.util.CommitException;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.URI;
import org.jabylon.cdo.connector.Modification;
import org.jabylon.cdo.connector.TransactionUtil;
import org.jabylon.common.util.PreferencesUtil;
import org.jabylon.properties.DiffKind;
import org.jabylon.properties.Project;
import org.jabylon.properties.ProjectLocale;
import org.jabylon.properties.ProjectVersion;
import org.jabylon.properties.PropertiesFactory;
import org.jabylon.properties.PropertiesPackage;
import org.jabylon.properties.PropertyFileDescriptor;
import org.jabylon.properties.PropertyFileDiff;
import org.jabylon.properties.Resolvable;
import org.jabylon.properties.Workspace;
import org.jabylon.properties.util.PropertyResourceUtil;
import org.jabylon.resources.persistence.PropertyPersistenceService;
import org.jabylon.rest.api.json.DefaultPermissionCallback;
import org.jabylon.rest.api.json.JSONEmitter;
import org.jabylon.security.CommonPermissions;
import org.jabylon.security.auth.AuthenticationService;
import org.jabylon.users.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Function;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.UncheckedExecutionException;

/**
 * TODO short description for ApiServlet.
 * <p>
 * Long description for ApiServlet.
 *
 * @author utzig
 */
public class ApiServlet extends HttpServlet implements Function<String, User>
// implements Servlet
{

    private static final String BASIC_AUTH_REALM = "BASIC realm=\"Jabylon API\"";
	private static final String BASIC_PREFIX = "BASIC ";
	/** field <code>serialVersionUID</code> */
    private static final long serialVersionUID = -1167994739560620821L;
    private Workspace workspace;
    private PropertyPersistenceService persistence;
	private AuthenticationService authService;
	private LoadingCache<String, User> cache;

	
    private static final Logger logger = LoggerFactory.getLogger(ApiServlet.class);

    public ApiServlet(Workspace workspace, AuthenticationService authService, PropertyPersistenceService persistence) {
        this.workspace = workspace;
        this.persistence = persistence;
        this.authService = authService;
    }

    /**
     * @see javax.servlet.Servlet#init(javax.servlet.ServletConfig)
     */
    @Override
    public void init(ServletConfig config) throws ServletException {
    	cache = CacheBuilder.newBuilder().concurrencyLevel(3).expireAfterAccess(2, TimeUnit.MINUTES).maximumSize(10).build(CacheLoader.from(this));
    }

    /**
     * @see javax.servlet.Servlet#getServletConfig()
     */
    @Override
    public ServletConfig getServletConfig() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        logger.info("API request to {}", req.getPathInfo());
        Resolvable child = getObject(req.getPathInfo());
        if (child == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Resource " + req.getPathInfo() + " does not exist");
            return;
        }
        if(!isAuthorized(req, false, child)) {
        	resp.setHeader("WWW-Authenticate", BASIC_AUTH_REALM);  
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED);  
        }
        else {
        	JSONEmitter emitter = new JSONEmitter(new DefaultPermissionCallback(getUser(req)));
        	StringBuilder result = new StringBuilder();
        	String depthString = req.getParameter("depth");
        	int depth = 1;
        	if (depthString != null)
        		depth = Integer.valueOf(depthString);
        	String type = req.getParameter("type");
        	if ("file".equals(type)) {
        		if (child instanceof PropertyFileDescriptor) {
        			serveFile((PropertyFileDescriptor) child, resp);
        		}
        		else {
        			serveArchive(child, resp);
        		}
        	}
        	else {
        		// TODO: use appendable
        		emitter.serialize(child, result, depth);
        		resp.getOutputStream().print(result.toString());        	
        	}        	
        }
        resp.getOutputStream().close();
    }

	protected Resolvable getObject(String path) throws IOException {
        String info = path;
        if (info == null)
            info = "";
        //FIXME: this is for backwards compatibility. Unify this with URI resolver
        if(info.startsWith("/workspace"))
            info = info.replaceFirst("/workspace", "");
        org.eclipse.emf.common.util.URI uri = org.eclipse.emf.common.util.URI.createURI(info);
        return workspace.resolveChild(uri);

    }

    @Override
    protected void doPut(final HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {

        URI uri = URI.createURI(req.getPathInfo());
        String[] segmentArray = uri.segments();
        if (segmentArray.length == 1)
            putProject(req, uri, resp);
        else if (segmentArray.length == 2)
            putVersion(req, uri, resp);
        else if (segmentArray.length == 3 && uri.hasTrailingPathSeparator())
            putLocale(req, uri, resp);
        else
            putPropertyFile(req, uri, resp);

    }

    private void putPropertyFile(HttpServletRequest req, URI uri, HttpServletResponse resp) throws IOException {
        // split between the project/version/locale portion and the rest
        String[] segmentArray = uri.segments();
        String[] projectPart = new String[2];
        final String[] descriptorPart = new String[segmentArray.length - projectPart.length];
        System.arraycopy(segmentArray, 0, projectPart, 0, projectPart.length);
        System.arraycopy(segmentArray, projectPart.length, descriptorPart, 0, descriptorPart.length);

        URI projectURI = URI.createHierarchicalURI(projectPart, null, null);
        Resolvable version = getObject(projectURI.path());
        if (version instanceof ProjectVersion) {
        	if(!isAuthorized(req, true, version)) {
        		resp.setHeader("WWW-Authenticate", BASIC_AUTH_REALM);  
        		resp.sendError(HttpServletResponse.SC_UNAUTHORIZED);  
        	}
        	else {
        		ProjectVersion projectVersion = (ProjectVersion) version;
        		URI descriptorLocation = URI.createHierarchicalURI(descriptorPart, null, null);
        		File folder = new File(projectVersion.absoluteFilePath().toFileString());
        		File propertyFile = new File(folder, descriptorLocation.path());
        		final PropertyFileDiff diff = PropertiesFactory.eINSTANCE.createPropertyFileDiff();
        		diff.setKind(propertyFile.isFile() ? DiffKind.MODIFY : DiffKind.ADD);
        		updateFile(propertyFile, req.getInputStream());
        		diff.setNewPath(descriptorLocation.path());
        		diff.setOldPath(descriptorLocation.path());
        		try {
        			TransactionUtil.commit(projectVersion, new Modification<ProjectVersion, ProjectVersion>() {
        				@Override
        				public ProjectVersion apply(ProjectVersion object) {
        					
        					object.partialScan(PreferencesUtil.getScanConfigForProject(object.getParent()), diff);
        					return object;
        				}
        			});
        			//TODO: why not use persistence service to store it instead?
        			persistence.clearCache();
        		} catch (CommitException e) {
        			resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Commit failed: " + e.getMessage());
        			logger.error("Commit failed", e);
        		}		
        	}
        } else
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Resource " + projectURI.path() + " does not exist");

    }

    private void putLocale(HttpServletRequest req, final URI uri, HttpServletResponse resp) throws IOException {
        URI truncated = uri.trimSegments(1);
        Resolvable object = getObject(truncated.path());
        if (object instanceof ProjectVersion) {
            ProjectVersion version = (ProjectVersion) object;
            if(!isAuthorized(req, true, version)) {
            	resp.setHeader("WWW-Authenticate", BASIC_AUTH_REALM);  
                resp.sendError(HttpServletResponse.SC_UNAUTHORIZED);  
            }
            else if (version.getChild(uri.lastSegment()) == null) {
                try {
                    TransactionUtil.commit(version, new Modification<ProjectVersion, ProjectVersion>() {
                        @Override
                        public ProjectVersion apply(ProjectVersion object) {

                            ProjectLocale locale = PropertiesFactory.eINSTANCE.createProjectLocale();
                            locale.setName(uri.lastSegment());
                            locale.setLocale((Locale) PropertiesFactory.eINSTANCE.createFromString(PropertiesPackage.Literals.LOCALE,
                                    uri.lastSegment()));
                            PropertyResourceUtil.addNewLocale(locale, object);
                            return object;
                        }
                    });
                } catch (CommitException e) {
                    logger.error("Commit failed", e);
                }
            }
        } else
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Version " + truncated.path() + " does not exist");
    }

    private void putVersion(HttpServletRequest req, final URI uri, HttpServletResponse resp) throws IOException {
        URI truncated = uri.trimSegments(1);
        Resolvable object = getObject(truncated.path());
        if (object instanceof Project) {
            Project project = (Project) object;
            if(!isAuthorized(req, true, project)) {
            	resp.setHeader("WWW-Authenticate", BASIC_AUTH_REALM);  
                resp.sendError(HttpServletResponse.SC_UNAUTHORIZED);  
            }
            else if (project.getChild(uri.lastSegment()) == null) {
                try {
                    TransactionUtil.commit(project, new Modification<Project, Project>() {
                        @Override
                        public Project apply(Project object) {
                            ProjectVersion child = PropertiesFactory.eINSTANCE.createProjectVersion();
                            ProjectLocale locale = PropertiesFactory.eINSTANCE.createProjectLocale();
                            child.getChildren().add(locale);
                            child.setTemplate(locale);
                            child.setName(uri.lastSegment());
                            object.getChildren().add(child);
                            return object;
                        }
                    });
                } catch (CommitException e) {
                    logger.error("Commit failed", e);
                }
            }
        } else
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Project " + truncated.path() + " does not exist");

    }

    private void putProject(HttpServletRequest req, final URI uri, HttpServletResponse resp) throws IOException {
        // TODO: evaluate JSON stream for settings
        if(!isAuthorized(req, true, workspace)) {
        	resp.setHeader("WWW-Authenticate", BASIC_AUTH_REALM);  
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED);  
        }
        else {
        	try {
        		TransactionUtil.commit(workspace, new Modification<Workspace, Workspace>() {
        			@Override
        			public Workspace apply(Workspace object) {
        				Project child = PropertiesFactory.eINSTANCE.createProject();
        				child.setName(uri.lastSegment());
        				object.getChildren().add(child);
        				return object;
        			}
        		});
        	} catch (CommitException e) {
        		logger.error("Commit failed", e);
        	}        	
        }

    }

    private void updateFile(File destination, ServletInputStream inputStream) throws IOException {
        byte[] buffer = new byte[1024];
        destination.getParentFile().mkdirs();
        FileOutputStream out = new FileOutputStream(destination);
        try {
            while (true) {
                int read = inputStream.read(buffer);
                if (read > 0)
                    out.write(buffer, 0, read);
                if (read < 0)
                    break;
            }
        } finally {
            out.close();
            inputStream.close();
        }
    }

    private void serveFile(PropertyFileDescriptor fileDescriptor, HttpServletResponse resp) throws IOException {

        URI path = fileDescriptor.absolutPath();
        File file = new File(path.path());
        ServletOutputStream outputStream = resp.getOutputStream();
        if (!file.exists()) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Resource " + fileDescriptor.fullPath() + " does not exist");
        } else {
        	resp.setContentLength((int) file.length());
            resp.setContentType("application/octet-stream");
            writeFileToStream(file, outputStream);

        }
        outputStream.flush();
    }
    
    protected void writeFileToStream(File file, OutputStream out) throws IOException {
        BufferedInputStream in = null;
        try {
            in = new BufferedInputStream(new FileInputStream(file));
            
            byte[] buffer = new byte[1024];
            while (true) {
                int read = in.read(buffer);
                if (read <= 0)
                    break;
                out.write(buffer, 0, read);
            }

        } finally {
            if (in != null)
                in.close();
        }
    }
    
    /** 
     * creates an archive that includes all children of the resolvable
     * @param parent
     * @param resp
     * @throws IOException 
     */
    private void serveArchive(Resolvable<?,?> parent, HttpServletResponse resp) throws IOException {
    	resp.setContentType("application/zip");
    	resp.setHeader("Content-disposition",MessageFormat.format("attachment;filename={0}.zip",parent.getName()));
    	ZipOutputStream out = new  ZipOutputStream(resp.getOutputStream());
    	addChildrenToArchive(out,parent);
    	out.close();
    	resp.flushBuffer();
		
	}

    @SuppressWarnings("unchecked")
	private void addChildrenToArchive(ZipOutputStream out, Resolvable<?, ?> parent) throws IOException {
		EList<Resolvable<?, ?>> children = (EList<Resolvable<?, ?>>) parent.getChildren();
		for (Resolvable<?, ?> child : children) {
			if (child instanceof PropertyFileDescriptor) {
				PropertyFileDescriptor descriptor = (PropertyFileDescriptor) child;
				
				File file = new File(descriptor.absoluteFilePath().path());
				if(!file.exists())
					continue;
				out.putNextEntry(new ZipEntry(((PropertyFileDescriptor) child).getLocation().path()));
				writeFileToStream(file, out);
			}
			else
				addChildrenToArchive(out, child);
		}
		
	}

	/**
     * @see javax.servlet.Servlet#getServletInfo()
     */
    @Override
    public String getServletInfo() {
        // TODO Auto-generated method stub
        return null;
    }

    /**
     * @see javax.servlet.Servlet#destroy()
     */
    @Override
    public void destroy() {
    	cache = null;

    }
    
    protected boolean isAuthorized(HttpServletRequest request, boolean isEdit, Resolvable<?, ?> target) {
    	User user = getUser(request);
    	if(user==null)
    		return false;
    	return CommonPermissions.hasPermission(user, target, isEdit ? CommonPermissions.ACTION_EDIT : CommonPermissions.ACTION_VIEW);
    }

	protected User getUser(HttpServletRequest request) {
        
    	String auth = request.getHeader("Authorization");  
        if (auth == null) {
        	//no auth header -> anonymous
            return authService.getAnonymousUser();
        }
        if (!auth.toUpperCase().startsWith(BASIC_PREFIX)) { 
            return null;  // we only do BASIC so far
        }
        // Encoded user and password come after "BASIC "
        String userpassEncoded = auth.substring(BASIC_PREFIX.length());
        try {
        	return cache.get(userpassEncoded);
		} catch (ExecutionException e) {
			// user is not known or not authorized
		} catch (UncheckedExecutionException e) {
			// user is not known or not authorized
		}
        
        return null;
        
    }
    
    private User authenticate(final String username, final String password) {
    	return authService.authenticateUser(username, password);
    }

	@Override
	public User apply(String authHeader) {
        byte[] decoded = Base64.decode(authHeader.getBytes());
        String userpassDecoded = new String(decoded);
        String[] userPass = userpassDecoded.split(":");
        User result = null;
        if(userPass.length==2)
        {
        	String username = userPass[0].isEmpty() ? null : userPass[0];
        	String password = userPass[1];
        	result = authenticate(username, password);
        }
        else if(userPass.length==1)
        {
        	//must be an auth token
        	result = authenticate(null, userPass[0]);
        }
        if(result!=null)
        	return result;
        throw new IllegalArgumentException("Invalid Credentials");
	}    

}
