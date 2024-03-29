package com.humaorie.maven.s3;

/*
 * Copyright 2001-2009 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.maven.wagon.AbstractWagon;
import org.apache.maven.wagon.ConnectionException;
import org.apache.maven.wagon.ResourceDoesNotExistException;
import org.apache.maven.wagon.TransferFailedException;
import org.apache.maven.wagon.authentication.AuthenticationException;
import org.apache.maven.wagon.authorization.AuthorizationException;
import org.apache.maven.wagon.events.TransferEvent;
import org.apache.maven.wagon.resource.Resource;
import org.jets3t.service.S3Service;
import org.jets3t.service.S3ServiceException;
import org.jets3t.service.acl.AccessControlList;
import org.jets3t.service.impl.rest.httpclient.RestS3Service;
import org.jets3t.service.model.S3Bucket;
import org.jets3t.service.model.S3Object;
import org.jets3t.service.security.AWSCredentials;

/*
 * @author Eric Redmond (original code)
 * @author Davide Angelocola (modifications)
 *
 */
public class S3Wagon extends AbstractWagon {

    private S3Service s3Service;
    private S3Bucket bucket;

    protected void openConnectionInternal() throws ConnectionException, AuthenticationException {
        if (authenticationInfo == null) {
            throw new AuthenticationException("Authentication info must be set prior to opening S3 connection.");
        }

        String passphrase = authenticationInfo.getPassphrase();
        String privateKey = authenticationInfo.getPrivateKey();
        AWSCredentials awsCredentials = new AWSCredentials(privateKey, passphrase);
        String host = getRepository().getHost();

        try {
            s3Service = new RestS3Service(awsCredentials);
            // original code used:
            //   bucket = s3Service.createBucket(host);
            bucket = s3Service.getOrCreateBucket(host);
        } catch (S3ServiceException e) {
            throw new ConnectionException("Cannot create service", e);
        }
    }

    protected void closeConnection() throws ConnectionException {
    }

    public void get(String resourceName, File destination)
            throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException {
        Resource resource = new Resource(resourceName);
        fireGetInitiated(resource, destination);
        S3Object s3Object = null;

        try {
            s3Object = s3Service.getObject(bucket, resourceName);
            BufferedInputStream bis = new BufferedInputStream(s3Object.getDataInputStream());
            FileOutputStream fos = new FileOutputStream(destination);
            byte[] readerBytes = new byte[1024 * 8]; // XXX
            int size = 0;

            while ((size = bis.read(readerBytes)) > 0) {
                fos.write(readerBytes, 0, size);
            }
        } catch (S3ServiceException e) {
            throw new ResourceDoesNotExistException("Cannot put object to S3", e);
        } catch (FileNotFoundException e) {
            throw new ResourceDoesNotExistException("", e);
        } catch (IOException e) {
            fireTransferError(resource, e, TransferEvent.REQUEST_GET);
            throw new TransferFailedException("", e);
        }

        fireGetCompleted(resource, destination);
    }

    public boolean getIfNewer(String resourceName, File destination, long timestamp)
            throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException {
        Resource resource = new Resource(resourceName);
        S3Object s3Object = null;

        try {
            s3Object = s3Service.getObjectDetails(bucket, resource.getName());
        } catch (S3ServiceException e) {
            fireTransferError(resource, e, TransferEvent.REQUEST_GET);
            throw new TransferFailedException("Cannot get object from S3", e);
        }

        Date lastModified = s3Object.getLastModifiedDate();

        if (lastModified.getTime() > timestamp) {
            get(resourceName, destination);
            return true;
        }

        return false;
    }

    public void put(File source, String destination)
            throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException {
        Resource resource = new Resource(destination);
        InputStream is;

        try {
            is = new FileInputStream(source);
        } catch (FileNotFoundException e) {
            fireTransferError(resource, e, TransferEvent.REQUEST_PUT);
            throw new ResourceDoesNotExistException("Cannot find source file", e);
        }

        firePutInitiated(resource, source);
        S3Object s3Object = new S3Object(resource.getName());
        // by default all objects are public
        s3Object.setAcl(AccessControlList.REST_CANNED_PUBLIC_READ);
        s3Object.setDataInputStream(is);
        s3Object.setContentLength(source.length());
        s3Object.setContentType("binary/octet-stream");

        try {
            s3Service.putObject(bucket, s3Object);
        } catch (S3ServiceException e) {
            fireTransferError(resource, e, TransferEvent.REQUEST_PUT);
            throw new TransferFailedException("Cannot put object to S3", e);
        }

        firePutCompleted(resource, source);
    }

    public List getFileList(String destinationDirectory)
            throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException {
        Resource resource = new Resource(destinationDirectory);

        try {
            S3Object[] filteredObjects = s3Service.listObjects(bucket, resource.getName(), null);

            if (filteredObjects == null || filteredObjects.length <= 0) {
                throw new ResourceDoesNotExistException("Could not find file: '" + resource + "'");
            }

            List ret = new ArrayList();
            
            for (int i = 0; i < filteredObjects.length; i++) {
                String key = filteredObjects[i].getKey();
                ret.add(key.substring(key.lastIndexOf('/') + 1));
            }

            return ret;
        } catch (S3ServiceException e) {
            throw new TransferFailedException("Error getting file list via S3", e);
        }
    }

    public boolean resourceExists(String resourceName)
            throws TransferFailedException, AuthorizationException {
        Resource resource = new Resource(resourceName);

        try {
            S3Object s3Object = s3Service.getObjectDetails(bucket, resource.getName());
            return s3Object != null;
        } catch (S3ServiceException e) {
            return false;
        }
    }
}
