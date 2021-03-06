/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.repository.pypi;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.pypi.internal.PyPiAttributes;
import org.sonatype.nexus.repository.pypi.internal.PyPiDataUtils;
import org.sonatype.nexus.repository.pypi.internal.PyPiFormat;
import org.sonatype.nexus.repository.pypi.internal.PyPiHostedFacet;
import org.sonatype.nexus.repository.rest.UploadDefinitionExtension;
import org.sonatype.nexus.repository.security.ContentPermissionChecker;
import org.sonatype.nexus.repository.security.VariableResolverAdapter;
import org.sonatype.nexus.repository.storage.StorageFacet;
import org.sonatype.nexus.repository.storage.TempBlob;
import org.sonatype.nexus.repository.transaction.TransactionalStoreBlob;
import org.sonatype.nexus.repository.upload.UploadHandlerSupport;
import org.sonatype.nexus.repository.upload.ComponentUpload;
import org.sonatype.nexus.repository.upload.UploadDefinition;
import org.sonatype.nexus.repository.upload.UploadResponse;
import org.sonatype.nexus.repository.view.PartPayload;
import org.sonatype.nexus.repository.view.payloads.TempBlobPartPayload;
import org.sonatype.nexus.rest.ValidationErrorsException;

import com.google.common.collect.ImmutableMap;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * @since 3.7
 */
@Named(PyPiFormat.NAME)
@Singleton
public class PyPiUploadHandler
    extends UploadHandlerSupport
{
  private UploadDefinition definition;

  private final ContentPermissionChecker contentPermissionChecker;

  private final VariableResolverAdapter variableResolverAdapter;

  @Inject
  public PyPiUploadHandler(final ContentPermissionChecker contentPermissionChecker,
                           @Named("simple") final VariableResolverAdapter variableResolverAdapter,
                           final Set<UploadDefinitionExtension> uploadDefinitionExtensions)
  {
    super(uploadDefinitionExtensions);
    this.contentPermissionChecker = contentPermissionChecker;
    this.variableResolverAdapter = variableResolverAdapter;
  }

  @Override
  public UploadResponse handle(final Repository repository, final ComponentUpload upload) throws IOException {
    PyPiHostedFacet facet = repository.facet(PyPiHostedFacet.class);

    StorageFacet storageFacet = repository.facet(StorageFacet.class);
    return TransactionalStoreBlob.operation.withDb(storageFacet.txSupplier())
        .throwing(IOException.class).call(() -> {
          PartPayload payload = upload.getAssetUploads().get(0).getPayload();
          try (TempBlob tempBlob = storageFacet.createTempBlob(payload, PyPiDataUtils.HASH_ALGORITHMS)) {
            Map<String, String> packageMetadata = facet.extractMetadata(payload, tempBlob);
            if (packageMetadata.isEmpty()) {
              throw new ValidationErrorsException("Unable to extract metadata from provided PyPi archive.");
            }

            String name = checkNotNull(packageMetadata.get(PyPiAttributes.P_NAME));
            String version = checkNotNull(packageMetadata.get(PyPiAttributes.P_VERSION));
            String filename = checkNotNull(payload.getName());

            String path = facet.createPackagePath(name, version, filename);
            ensurePermitted(repository.getName(), PyPiFormat.NAME, path, coordinatesFromMetadata(packageMetadata));

            return new UploadResponse(facet.upload(packageMetadata, new TempBlobPartPayload(payload, tempBlob)));
          }
        });
  }

  private Map<String, String> coordinatesFromMetadata(final Map<String, String> packageMetadata) {
    return ImmutableMap.of(PyPiAttributes.P_NAME, packageMetadata.get(PyPiAttributes.P_NAME), PyPiAttributes.P_VERSION,
        packageMetadata.get(PyPiAttributes.P_VERSION));
  }

  @Override
  public UploadDefinition getDefinition() {
    if (definition == null) {
      definition = getDefinition(PyPiFormat.NAME, false);
    }
    return definition;
  }

  @Override
  public VariableResolverAdapter getVariableResolverAdapter() {
    return variableResolverAdapter;
  }

  @Override
  public ContentPermissionChecker contentPermissionChecker() {
    return contentPermissionChecker;
  }
}
