/**
 * (C) Copyright IBM Corp. 2015, 2016
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
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
 *
 */

package com.ibm.stocator.fs.common;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.Properties;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.stocator.fs.common.exception.ConfigurationParseException;

import static com.ibm.stocator.fs.common.Constants.HADOOP_ATTEMPT;

public class Utils {

  public static final String BAD_HOST = " hostname '%s' must be in the form container.service";

  /*
   * Logger
   */
  private static final Logger LOG = LoggerFactory.getLogger(Utils.class);

  /*
  * Time pattern
  */
  private static final String TIME_PATTERN = "EEE, d MMM yyyy hh:mm:ss zzz";

  /**
   * IOException if the host name is not comply with container.service
   *
   * @param hostname hostname
   * @return IOException
   */
  private static IOException badHostName(String hostname) {
    return new IOException(String.format(BAD_HOST, hostname));
  }

  /**
   * Extracts container name from the container.service
   *
   * @param hostname hostname to split
   * @return the container
   * @throws IOException if hostname is invalid
   */
  public static String getContainerName(String hostname) throws IOException {
    int i = hostname.indexOf(".");
    if (i <= 0) {
      throw badHostName(hostname);
    }
    // decode it if encoded
    return decodeURI(hostname.substring(0, i));
  }

  
  
  /**
   * Extracts service name from the container.service
   *
   * @param hostname hostname
   * @return the separated out service name
   * @throws IOException if the hostname was invalid
   */
  public static String getServiceName(String hostname) throws IOException {
    int i = hostname.indexOf(".");
    if (i <= 0) {
      throw badHostName(hostname);
    }
    String service = hostname.substring(i + 1);
    if (service.isEmpty() || service.contains(".")) {
      throw badHostName(hostname);
    }
    return decodeURI(service);
  }

  /**
   * Test if hostName of the form container.service
   *
   * @param uri schema URI
   * @return true if hostName of the form container.service
   */
  public static boolean validSchema(URI uri) {
    LOG.trace("Checking schema {}", uri.toString());
    String hostName = Utils.getHost(uri);
    LOG.trace("Got hostname as {}", hostName);
    int i = hostName.indexOf(".");
    if (i < 0) {
      return false;
    }
    String service = hostName.substring(i + 1);
    LOG.trace("Got service as {}", service);
    if (service.isEmpty() || service.contains(".")) {
      return false;
    }
    return true;
  }

  public static boolean validSchema(String path) throws IOException {
    try {
      return validSchema(new URI(path));
    } catch (URISyntaxException e) {
      throw new IOException(e.getMessage());
    }
  }

  /**
   * Extract host name from the URI
   *
   * @param uri object store uri
   * @return host name
   */
  public static String getHost(URI uri) {
    String host = uri.getHost();
    if (host != null) {
      return host;
    }
    
    return getHost(uri.toString());
  }


  /**
   * Extract host name from the URI
   *
   * @param uri object store uri
   * @return host name
   */
  public static String getHost(String uri) {
    
    int sInd = uri.indexOf("//") + 2;
    uri = uri.substring(sInd);
    
    int eInd = uri.contains("/") ? uri.indexOf("/"): uri.length();
    uri = uri.substring(0,eInd);
    
    return decodeURI(uri);
  }

  /**
   * Get a mandatory configuration option
   *
   * @param props property set
   * @param key key
   * @return value of the configuration
   * @throws IOException if there was no match for the key
   */
  public static String getOption(Properties props, String key) throws IOException {
    String val = props.getProperty(key);
    if (val == null) {
      throw new IOException("Undefined property: " + key);
    }
    return val;
  }

  /**
   * Read key from core-site.xml and parse it to Swift configuration
   *
   * @param conf source configuration
   * @param prefix configuration key prefix
   * @param alternativePrefix alternative prefix
   * @param key key in the configuration file
   * @param props destination property set
   * @param propsKey key in the property set
   * @param required if the key is mandatory
   * @throws ConfigurationParseException if there was no match for the key
   */

  public static void updateProperty(Configuration conf, String prefix, String alternativePrefix,
      String key, Properties props, String propsKey,
      boolean required) throws ConfigurationParseException {
    String val = conf.get(prefix + key);
    if (val == null) {
      // try alternative key
      val = conf.get(alternativePrefix + key);
      LOG.trace("Trying alternative key {}{}", alternativePrefix, key);
    }
    if (required && val == null) {
      throw new ConfigurationParseException("Missing mandatory configuration: " + key);
    }
    if (val != null) {
      props.setProperty(propsKey, val.trim());
    }
  }

  /**
   * Extract Hadoop Task ID from path
   * @param path path to extract attempt id
   * @param identifier identifier to extract id
   * @return task id
   */
  public static String extractTaskID(String path, String identifier) {
    LOG.debug("extract task id for {}", path);
    if (path.contains(HADOOP_ATTEMPT)) {
      String prf = path.substring(path.indexOf(HADOOP_ATTEMPT));
      if (prf.contains("/")) {
        return TaskAttemptID.forName(prf.substring(0, prf.indexOf("/"))).toString();
      }
      return TaskAttemptID.forName(prf).toString();
    } else if (identifier != null && path.contains(identifier)) {
      int ind = path.indexOf(identifier);
      String prf = path.substring(ind + identifier.length());
      int boundary = prf.length();
      if (prf.indexOf("/") > 0) {
        boundary = prf.indexOf("/");
      }
      String taskID =  prf.substring(0, boundary);
      LOG.debug("extracted task id {} for {}", taskID, path);
      return taskID;
    }
    return null;
  }

  /**
   * Transform http://hostname/v1/auth_id/container/object to
   * http://hostname/v1/auth_id
   *
   * @param publicURL public url
   * @return accessURL access url
   * @throws IOException if path is malformed
   */
  public static String extractAccessURL(String publicURL) throws IOException {
    try {
      String hostName = new URI(publicURL).getAuthority();
      int  start = publicURL.indexOf("//") + 2 + hostName.length() + 1;
      for (int i = 0; i < 2; i++) {
        start = publicURL.indexOf("/", start) + 1;
      }
      String authURL = publicURL.substring(0, start);
      if (authURL.endsWith("/")) {
        authURL = authURL.substring(0, authURL.length() - 1);
      }
      return authURL;
    } catch (URISyntaxException e) {
      throw new IOException("Public URL: " + publicURL + " is not valid");
    }
  }

  /**
   * Extracts container name from http://hostname/v1/auth_id/container/object
   *
   * @param publicURL public url
   * @param accessURL access url
   * @return container name
   */
  public static String extractDataRoot(String publicURL, String accessURL) {
    String reminder = publicURL.substring(accessURL.length() + 1);
    String container = null;
    if (reminder.indexOf("/") > 0) {
      container =  reminder.substring(0, reminder.indexOf("/"));
    } else {
      container = reminder;
    }
    if (container.endsWith("/")) {
      container = container.substring(0, container.length() - 1);
    }
    return container;
  }

  /**
   * Extracts container/object  from http://hostname/v1/auth_id/container/object
   *
   * @param publicURL pubic url
   * @param accessURL access url
   * @return reminder of the URI
   */
  public static String extractReminder(String publicURL, String accessURL) {
    return publicURL.substring(accessURL.length());
  }

  public static void closeWithoutException(Closeable is) {
    if (is != null) {
      try {
        is.close();
      } catch (IOException ex) {
        LOG.debug("Ignore failure in closing the Closeable", ex);
      }
    }
  }

  public static boolean shouldAbort() {
    return Thread.interrupted();
  }

  /**
   * Transforms last modified time stamp from String to the long format
   *
   * @param strTime time in string format as returned from Swift
   * @return time in long format
   * @throws IOException if failed to parse time stamp
   */
  public static long lastModifiedAsLong(String strTime) throws IOException {
    final SimpleDateFormat simpleDateFormat = new SimpleDateFormat(TIME_PATTERN,
        Locale.US);
    try {
      long lastModified = simpleDateFormat.parse(strTime).getTime();
      if (lastModified == 0) {
        lastModified = System.currentTimeMillis();
      }
      return lastModified;
    } catch (ParseException e) {
      throw new IOException("Failed to parse " + strTime, e);
    }
  }
  
  /**
   * Decodes URI or part of it
   * 
   * @param uri encoded with URI ctor (not URIEncoder) according to RFC 3986
   * @return decoded uri or part of it
   */
  private static String decodeURI(String uri) {
	  return uri.replace("%20", " ").replace("%2A", "*").replace("%7E", "~");
  }
}
