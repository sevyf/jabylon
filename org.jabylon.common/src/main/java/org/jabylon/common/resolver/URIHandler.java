/**
 * (C) Copyright 2013 Jabylon (http://www.jabylon.org) and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.jabylon.common.resolver;

import org.eclipse.emf.common.util.URI;

public interface URIHandler {

    boolean canHandle(URI uri);

    Object resolve(URI uri);
    
    boolean canHandle(Object o);
    
    URI toURI(Object o);

}
