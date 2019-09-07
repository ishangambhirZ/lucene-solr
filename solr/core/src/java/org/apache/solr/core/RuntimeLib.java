/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.solr.cloud.CloudUtil;
import org.apache.solr.common.MapWriter;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.util.StrUtils;
import org.apache.solr.util.CryptoKeys;
import org.apache.solr.util.SimplePostTool;
import org.apache.solr.util.plugin.PluginInfoInitialized;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.solr.common.params.CommonParams.NAME;

/**
 * This represents a Runtime Jar. A jar requires two details , name and version
 */
public class RuntimeLib implements PluginInfoInitialized, AutoCloseable, MapWriter {
  public static final String TYPE = "runtimeLib";
  public static final String SHA256 = "sha256";
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private final CoreContainer coreContainer;
  private String name, version, sig, sha256;
  private BlobRepository.BlobContentRef<ByteBuffer> blobContentRef;
  ByteBuffer buffer;
  private boolean verified = false;
  int znodeVersion = -1;

  @Override
  public void writeMap(EntryWriter ew) throws IOException {
    ew.putIfNotNull(NAME, name);
    ew.putIfNotNull(version, version);
    ew.putIfNotNull("sha256", sha256);
    ew.putIfNotNull("sig", sig);
    if (znodeVersion > -1) {
      ew.put(ConfigOverlay.ZNODEVER, znodeVersion);
    }
  }
  public int getZnodeVersion(){
    return znodeVersion;
  }

  public RuntimeLib(CoreContainer coreContainer) {
    this.coreContainer = coreContainer;
  }

  public static boolean isEnabled() {
    return "true".equals(System.getProperty("enable.runtime.lib"));
  }

  public static List<RuntimeLib> getLibObjects(SolrCore core, List<PluginInfo> libs) {
    List<RuntimeLib> l = new ArrayList<>(libs.size());
    for (PluginInfo lib : libs) {
      RuntimeLib rtl = new RuntimeLib(core.getCoreContainer());
      try {
        rtl.init(lib);
      } catch (Exception e) {
        log.error("error loading runtime library", e);
      }
      l.add(rtl);
    }
    return l;
  }

  @Override
  public void init(PluginInfo info) {
    name = info.attributes.get(NAME);
    sha256 = info.attributes.get(SHA256);
    sig = info.attributes.get("sig");

    if (sha256 == null) {
      Object v = info.attributes.get("version");
      if (name == null || v == null) {
        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "runtimeLib must have name and version");
      }
      version = String.valueOf(v);
    } else {
      try {
        buffer = coreContainer.getBlobRepository().getBlob(sha256);
      } catch (IOException e) {
        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
      }
      if(buffer == null){
        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "No such blob : "+ sha256);
      }

      String digest = BlobRepository.sha256Digest(buffer);
      if (!sha256.equals(digest)) {
        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, StrUtils.formatString(BlobRepository.INVALID_JAR_MSG,  sha256, digest));
      }
      verifyJarSignature(buffer);

      log.debug("dynamic library verified , sha256: {}",  sha256);

    }

  }



  void loadJar() {
    if (buffer != null) return;
    synchronized (this) {
      if (buffer != null) return;
      if(sha256 == null){
        //legacy type
        blobContentRef = coreContainer.getBlobRepository().getBlobIncRef(name + "/" + version);
        buffer = blobContentRef.blob.get();
      } else {
        //loaded from blob repo
        try {
          buffer = coreContainer.getBlobRepository().getBlob(sha256);
        } catch (IOException e) {
          throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
        }
      }

    }
  }

  public String getName() {
    return name;
  }

  public String getVersion() {
    return version;
  }

  public String getSig() {
    return sig;

  }

  public String getSha256() {
    return sha256;
  }

  public ByteBuffer getFileContent(String entryName) throws IOException {
    if (buffer == null)
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "jar not available: " + name);
    return getFileContent(buffer, entryName);

  }

  public ByteBuffer getFileContent(ByteBuffer buff, String entryName) throws IOException {
    ByteArrayInputStream zipContents = new ByteArrayInputStream(buff.array(), buff.arrayOffset(), buff.limit());
    ZipInputStream zis = new ZipInputStream(zipContents);
    try {
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null) {
        if (entryName == null || entryName.equals(entry.getName())) {
          SimplePostTool.BAOS out = new SimplePostTool.BAOS();
          byte[] buffer = new byte[2048];
          int size;
          while ((size = zis.read(buffer, 0, buffer.length)) != -1) {
            out.write(buffer, 0, size);
          }
          out.close();
          return out.getByteBuffer();
        }
      }
    } finally {
      zis.closeEntry();
    }
    return null;
  }

  @Override
  public void close() throws Exception {
    if (blobContentRef != null) coreContainer.getBlobRepository().decrementBlobRefCount(blobContentRef);
  }

  public void verify() throws Exception {
    if (verified) return;
    if (buffer == null) {
      log.error("Calling verify before loading the jar");
      return;
    }

    if (!coreContainer.isZooKeeperAware())
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Signing jar is possible only in cloud");
    verifyJarSignature(buffer);
  }

  void verifyJarSignature(ByteBuffer buf) {
    Map<String, byte[]> keys = getPublicKeys();
    if (keys.isEmpty()) {
      if (sig == null) {
        verified = true;
        return;
      } else {
        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "No public keys are available in ZK to verify signature for runtime lib  " + name);
      }
    } else if (sig == null) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, StrUtils.formatString("runtimelib {0} should be signed with one of the keys in ZK /keys/exe ", name));
    }

    try {
      String matchedKey = new CryptoKeys(keys).verify(sig, buf);
      if (matchedKey == null)
        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "No key matched signature for jar : " + name + " version: " + version);
      log.info("Jar {} signed with {} successfully verified", name, matchedKey);
    } catch (Exception e) {
      log.error("Signature verifying error ", e);
      if (e instanceof SolrException) throw (SolrException) e;
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Error verifying key ", e);
    }
  }

  protected Map<String, byte[]> getPublicKeys() {
    return CloudUtil.getTrustedKeys(coreContainer.getZkController().getZkClient(), "exe");
  }
}