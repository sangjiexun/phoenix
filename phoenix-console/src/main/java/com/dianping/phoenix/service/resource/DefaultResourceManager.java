package com.dianping.phoenix.service.resource;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.codehaus.plexus.logging.LogEnabled;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.Initializable;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.InitializationException;
import org.unidal.lookup.ContainerHolder;
import org.unidal.lookup.annotation.Inject;

import com.dianping.phoenix.agent.resource.entity.Domain;
import com.dianping.phoenix.agent.resource.entity.Host;
import com.dianping.phoenix.agent.resource.entity.Product;
import com.dianping.phoenix.agent.resource.entity.Resource;
import com.dianping.phoenix.agent.resource.transform.DefaultSaxParser;
import com.dianping.phoenix.agent.resource.transform.DefaultXmlBuilder;
import com.dianping.phoenix.configure.ConfigManager;
import com.dianping.phoenix.console.page.home.Payload;
import com.dianping.phoenix.device.entity.Device;
import com.dianping.phoenix.service.resource.cmdb.DeviceManager;
import com.dianping.phoenix.service.resource.netty.AgentStatusFetcher;
import com.dianping.phoenix.service.visitor.DeviceVisitor;
import com.dianping.phoenix.service.visitor.resource.AgentFilterStrategy;
import com.dianping.phoenix.service.visitor.resource.FilteredResourceBuilder;
import com.dianping.phoenix.service.visitor.resource.JarFilterStrategy;
import com.dianping.phoenix.service.visitor.resource.ResourceAnalyzer;
import com.dianping.phoenix.utils.StringUtils;

public class DefaultResourceManager extends ContainerHolder implements ResourceManager, Initializable, LogEnabled {
	@Inject
	private DeviceManager m_deviceManager;

	@Inject
	protected ConfigManager m_configManager;

	@Inject
	private AgentStatusFetcher m_agentStatusFetcher;

	private Logger m_logger;
	private DefaultXmlBuilder m_xmlBuilder = new DefaultXmlBuilder();

	private AtomicReference<Resource> m_resource = new AtomicReference<Resource>();
	private AtomicReference<Map<String, Domain>> m_domains = new AtomicReference<Map<String, Domain>>();

	private AtomicReference<Map<String, Map<String, List<Device>>>> m_deviceCatalog = new AtomicReference<Map<String, Map<String, List<Device>>>>();

	private AtomicReference<Set<String>> m_jarNameSet = new AtomicReference<Set<String>>();
	private AtomicReference<Set<String>> m_agentVersionSet = new AtomicReference<Set<String>>();

	private AtomicReference<Map<String, Set<String>>> m_domainToJarNameSet = new AtomicReference<Map<String, Set<String>>>();

	private Resource m_resourceCache;
	private String m_cachePath;

	private Long m_lastUpdateTime = 0L;

	private boolean m_inited = false;

	@Override
	public void initialize() throws InitializationException {
		m_cachePath = m_configManager.getResourceCachePath();

		loadReosurceFromCacheFile();

		m_logger.info("Starting device catalog watchdog thread ...");
		Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(new Runnable() {
			@Override
			public void run() {
				try {
					m_deviceCatalog.set(m_deviceManager.getDeviceCatalog());
				} catch (ConnectException e) {
					m_logger.error("Can not get device catalog from cmdb.", e);
				}
			}
		}, m_configManager.getResourceInfoRefreshIntervalMin(), m_configManager.getResourceInfoRefreshIntervalMin(),
				TimeUnit.MINUTES);

		m_logger.info("Starting agent status watchdog thread ...");
		Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(new Runnable() {
			@Override
			public void run() {
				try {
					if (!m_inited) {
						m_deviceCatalog.set(m_deviceManager.getDeviceCatalog());
						m_inited = true;
					}
					refreshAgentStatus();
					cacheResource(m_cachePath);
				} catch (Exception e) {
					m_logger.error("Refresh agent status failed.", e);
				}
			}
		}, 0, m_configManager.getAgentFetchIntervalMin(), TimeUnit.MINUTES);
	}

	private void loadReosurceFromCacheFile() {
		Resource resource = getResourceFromCacheFile(m_cachePath);

		generateMetaInformation(resource);

		m_resourceCache = resource;
		m_resource.set(resource);
	}

	private void refreshAgentStatus() {
		long current = System.currentTimeMillis();

		Map<String, Map<String, List<Device>>> catalog = m_deviceCatalog.get();

		if (catalog != null) {
			Resource resource = new Resource();

			ExecutorService executor = Executors.newCachedThreadPool();
			CountDownLatch latch = new CountDownLatch(catalog.size());

			for (Entry<String, Map<String, List<Device>>> productEntry : catalog.entrySet()) {
				Product product = new Product(productEntry.getKey());
				executor.execute(new EnrichProductTask(product, productEntry.getValue(), latch, m_configManager
						.getAgentFetchIntervalMin() * 60 / catalog.size()));
				resource.addProduct(product);
			}

			try {
				latch.await();
			} catch (InterruptedException e1) {
				e1.printStackTrace();
			}

			executor.shutdown();

			synchronized (m_lastUpdateTime) {
				if (current > m_lastUpdateTime) {
					generateMetaInformation(resource);

					m_resourceCache = resource;
					m_resource.set(resource);
					m_lastUpdateTime = System.currentTimeMillis();
				}
			}
		}
	}

	private class EnrichProductTask implements Runnable {
		private Product m_product;
		private Map<String, List<Device>> m_productCatalog;
		private CountDownLatch m_latch;
		private long m_timeout;

		public EnrichProductTask(Product product, Map<String, List<Device>> productCatalog, CountDownLatch latch,
				long timeout) {
			m_product = product;
			m_productCatalog = productCatalog;
			m_latch = latch;
			m_timeout = timeout;
		}

		@Override
		public void run() {
			try {
				for (Entry<String, List<Device>> domainEntry : m_productCatalog.entrySet()) {
					Domain domain = new Domain(domainEntry.getKey());

					m_logger.info(String.format("Fetching agent status: [product= %s]\t[domain= %s]",
							m_product.getName(), domain.getName()));
					enrichDomain(domain, domainEntry.getValue());

					m_product.addDomain(domain);
					try {
						TimeUnit.SECONDS.sleep(m_timeout / m_productCatalog.size());
					} catch (InterruptedException e) {
					}
				}
			} catch (Exception e) {
				m_logger.error(String.format("Fetch Product[%s] agent status failed.", m_product.getName()), e);
			} finally {
				m_latch.countDown();
			}
		}
	}

	private void generateMetaInformation(Resource resource) {
		ResourceAnalyzer analyzer = new ResourceAnalyzer(resource);

		setAgentVersionSet(analyzer.getAgentVersionSet());
		setJarNameSet(analyzer.getJarNameSet());
		setDomainToJarNameSet(analyzer.getDomainToJarNameSet());
		setDomains(analyzer.getDomains());
	}

	@Override
	public void enableLogging(Logger logger) {
		m_logger = logger;
	}

	public void setDomains(Map<String, Domain> domains) {
		m_domains.set(domains);
	}

	@Override
	public Resource getResource() {
		return m_resource.get();
	}

	private void cacheResource(String cachePath) {
		File cache = new File(cachePath, "resource-cache.xml");
		if (!cache.exists()) {
			try {
				cache.getParentFile().mkdirs();
				cache.createNewFile();
			} catch (Exception e) {
				m_logger.warn("Can not create resource-cache.xml!", e);
			}
		}
		if (cache.exists()) {
			FileWriter writer = null;
			try {
				String cacheStr = m_xmlBuilder.buildXml(getResource());
				writer = new FileWriter(cache);
				writer.write(cacheStr);
				writer.close();
			} catch (Exception e) {
				m_logger.warn("Write resource to resource-cache.xml failed.", e);
			} finally {
				if (writer != null) {
					try {
						writer.close();
					} catch (IOException e) {
						m_logger.warn("Close resource-cache.xml writer failed.");
					}
				}
			}
		}
	}

	private void enrichDomain(Domain domain, List<Device> devices) {
		domain.setDescription("N/A");
		DeviceVisitor visitor = new DeviceVisitor();
		try {
			for (Device device : devices) {
				Host host = new Host();
				device.accept(visitor.setHost(host));
				domain.addHost(host);
			}
			m_agentStatusFetcher.fetchPhoenixAgentStatus(new ArrayList<Host>(domain.getHosts().values()));
		} catch (Exception e) {
			domain = getDomainFromCache(domain.getName());
		}
	}

	private Domain getDomainFromCache(String domainName) {
		if (m_resourceCache == null) {
			m_resourceCache = getResourceFromCacheFile(m_cachePath);
		}
		for (Product product : m_resourceCache.getProducts().values()) {
			if (product.getDomains() != null && product.getDomains().containsKey(domainName)) {
				return product.getDomains().get(domainName);
			}
		}
		return null;
	}

	protected Resource getResourceFromCacheFile(String cachePath) {
		m_logger.info("Fetch agent status from cache file.");

		File cache = new File(cachePath, "resource-cache.xml");
		if (cache.exists()) {
			try {
				System.out.println(cache.getAbsolutePath());
				Resource res = DefaultSaxParser.parse(new FileInputStream(cache));
				return res;
			} catch (Exception e) {
				m_logger.warn("Can not parse resource cache file.");
			}
		} else {
			m_logger.warn("Can not find resource cache file.");
		}
		return new Resource();
	}

	@Override
	public Domain updateDomainManually(String name) {
		if (StringUtils.isBlank(name)) {
			return null;
		}

		List<Device> devices;
		try {
			devices = m_deviceManager.getDevices(name);
			Domain domain = new Domain(name);
			enrichDomain(domain, devices);

			if (domain != null) {
				Resource resource = getResource();
				for (Product product : resource.getProducts().values()) {
					if (product.getDomains().containsKey(name)) {
						product.getDomains().put(name, domain);
						m_domains.get().put(name, domain);
						break;
					}
				}
			}
			return domain;
		} catch (ConnectException e) {
			m_logger.error("Manually update domain info failed.", e);
			return null;
		}
	}

	@Override
	public Domain getDomain(String name) {
		Map<String, Domain> domains = m_domains.get();
		if (domains != null && domains.containsKey(name)) {
			return domains.get(name);
		}
		return null;
	}

	@Override
	public List<Product> getProducts() {
		return new ArrayList<Product>(getResource().getProducts().values());
	}

	@Override
	public List<Product> getFilteredProducts(Payload payload) {
		Resource resource = new FilteredResourceBuilder("phoenix-agent".equals(payload.getType())
				? new AgentFilterStrategy(getResource(), payload)
				: new JarFilterStrategy(getResource(), payload)).getFilteredResource();

		return new ArrayList<Product>(resource.getProducts().values());
	}

	@Override
	public Domain getFilteredDomain(Payload payload, String name) {
		Resource resource = new FilteredResourceBuilder("phoenix-agent".equals(payload.getType())
				? new AgentFilterStrategy(getResource(), payload)
				: new JarFilterStrategy(getResource(), payload)).getFilteredResource();

		for (Product product : resource.getProducts().values()) {
			if (product.getDomains().containsKey(name)) {
				return product.getDomains().get(name);
			}
		}

		return new Domain(name);
	}

	@Override
	public Set<String> getAgentVersionSet() {
		return m_agentVersionSet.get();
	}

	@Override
	public Set<String> getJarNameSet() {
		return m_jarNameSet.get();
	}

	@Override
	public Set<String> getJarNameSet(String domainName) {
		Map<String, Set<String>> map = m_domainToJarNameSet.get();
		return map.containsKey(domainName) ? map.get(domainName) : null;
	}

	void setAgentVersionSet(Set<String> set) {
		m_agentVersionSet.set(set);
	}

	void setJarNameSet(Set<String> set) {
		m_jarNameSet.set(set);
	}

	void setDomainToJarNameSet(Map<String, Set<String>> map) {
		m_domainToJarNameSet.set(map);
	}
}
