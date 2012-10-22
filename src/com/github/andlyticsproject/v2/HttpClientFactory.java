package com.github.andlyticsproject.v2;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.zip.GZIPInputStream;

import org.apache.http.Header;
import org.apache.http.HeaderElement;
import org.apache.http.HttpEntity;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.HttpStatus;
import org.apache.http.HttpVersion;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.params.HttpClientParams;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.entity.HttpEntityWrapper;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.params.HttpProtocolParams;
import org.apache.http.protocol.HTTP;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;

public class HttpClientFactory {

	private static final String HEADER_ACCEPT_ENCODING = "Accept-Encoding";
	private static final String ENCODING_GZIP = "gzip";
	private static final String HEADER_CACHE_CONTROL = "Cache-Control";
	private static final String HEADER_PRAGMA = "Pragma";
	private static final String NO_CACHE = "no-cache";

	private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 6.1; WOW64; rv:15.0) Gecko/20100101 Firefox/15.0";
	private static final String ACCEPT_VALUE = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";
	private static final String ACCEPT_LANG_VALUE = "en-us,en;q=0.5";
	private static final String ACCEPT_CHARSET_VALUE = "ISO-8859-1,utf-8;q=0.7,*;q=0.7";
	// TODO do we need this?
	private static final String KEEP_ALIVE_VALUE = "115";

	private HttpClientFactory() {
	}

	public static DefaultHttpClient createDevConsoleHttpClient(int timeoutMillis) {
		DefaultHttpClient result = createDefaultClient(timeoutMillis);
		result.addRequestInterceptor(new HttpRequestInterceptor() {
			public void process(HttpRequest request, HttpContext context) {
				addCommonHeaders(request);
			}
		});
		addGzipInterceptor(result);

		return result;
	}

	private static DefaultHttpClient createDefaultClient(int timeoutMillis) {
		HttpParams params = new BasicHttpParams();
		HttpConnectionParams.setConnectionTimeout(params, timeoutMillis);
		HttpConnectionParams.setSoTimeout(params, timeoutMillis);
		HttpProtocolParams.setUserAgent(params, USER_AGENT);
		HttpClientParams.setRedirecting(params, true);
		HttpProtocolParams.setVersion(params, HttpVersion.HTTP_1_1);
		HttpProtocolParams.setContentCharset(params, HTTP.UTF_8);
		HttpProtocolParams.setUseExpectContinue(params, false);

		HttpConnectionParams.setSoTimeout(params, timeoutMillis);
		HttpConnectionParams.setConnectionTimeout(params, timeoutMillis);

		SSLSocketFactory sf = SSLSocketFactory.getSocketFactory();
		sf.setHostnameVerifier(SSLSocketFactory.BROWSER_COMPATIBLE_HOSTNAME_VERIFIER);
		SchemeRegistry registry = new SchemeRegistry();
		registry.register(new Scheme("http", PlainSocketFactory.getSocketFactory(), 80));
		registry.register(new Scheme("https", sf, 443));

		return new DefaultHttpClient(new ThreadSafeClientConnManager(params, registry), params);
	}

	private static void addGzipInterceptor(DefaultHttpClient result) {
		result.addResponseInterceptor(new HttpResponseInterceptor() {
			public void process(HttpResponse response, HttpContext context) {
				// Inflate any responses compressed with gzip
				final HttpEntity entity = response.getEntity();
				final Header encoding = entity.getContentEncoding();
				if (encoding != null) {
					for (HeaderElement element : encoding.getElements()) {
						if (element.getName().equalsIgnoreCase(ENCODING_GZIP)) {
							response.setEntity(new InflatingEntity(response.getEntity()));
							break;
						}
					}
				}
			}
		});
	}

	public static ResponseHandler<String> createResponseHandler(
			Class<? extends RuntimeException> httpErrorExceptionClass) {
		return new StringResponseHandler(httpErrorExceptionClass);
	}

	private static void addCommonHeaders(HttpRequest request) {
		if (!request.containsHeader(HEADER_ACCEPT_ENCODING)) {
			request.addHeader(HEADER_ACCEPT_ENCODING, ENCODING_GZIP);
		}
		if (!request.containsHeader(HEADER_CACHE_CONTROL)) {
			request.addHeader(HEADER_CACHE_CONTROL, NO_CACHE);
		}
		if (!request.containsHeader(HEADER_PRAGMA)) {
			request.addHeader(HEADER_PRAGMA, NO_CACHE);
		}

		// overwrite?
		request.addHeader("Accept", ACCEPT_VALUE);
		request.addHeader("Accept-Language", ACCEPT_LANG_VALUE);
		request.addHeader("Accept-Charset", ACCEPT_CHARSET_VALUE);
		request.addHeader("Keep-Alive", KEEP_ALIVE_VALUE);
	}

	static class InflatingEntity extends HttpEntityWrapper {
		public InflatingEntity(HttpEntity wrapped) {
			super(wrapped);
		}

		@Override
		public InputStream getContent() throws IOException {
			return new GZIPInputStream(wrappedEntity.getContent());
		}

		@Override
		public long getContentLength() {
			return -1;
		}
	}

	static class StringResponseHandler implements ResponseHandler<String> {

		private Class<? extends RuntimeException> httpErrorExceptionClass;

		public StringResponseHandler(Class<? extends RuntimeException> exceptionClass) {
			this.httpErrorExceptionClass = exceptionClass;
		}

		public String handleResponse(HttpResponse response) throws ClientProtocolException,
				IOException {
			HttpEntity entity = response.getEntity();
			if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
				if (entity != null) {
					entity.consumeContent();
				}

				try {
					Constructor<? extends RuntimeException> ctor = httpErrorExceptionClass
							.getConstructor(String.class);
					throw ctor.newInstance("Server error: " + response.getStatusLine());
				} catch (InstantiationException e) {
					throw new RuntimeException(e);
				} catch (IllegalAccessException e) {
					throw new RuntimeException(e);
				} catch (NoSuchMethodException e) {
					throw new RuntimeException(e);
				} catch (IllegalArgumentException e) {
					throw new RuntimeException(e);
				} catch (InvocationTargetException e) {
					throw new RuntimeException(e);
				}
			}

			String responseStr = null;
			if (entity != null) {
				responseStr = EntityUtils.toString(entity);
			}

			return responseStr;
		}
	}

}
