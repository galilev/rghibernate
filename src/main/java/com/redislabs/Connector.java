package com.redislabs;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.boot.registry.StandardServiceInitiator;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.boot.registry.internal.StandardServiceRegistryImpl;
import org.hibernate.service.Service;
import org.hibernate.service.spi.ServiceRegistryImplementor;

import gears.ExecutionMode;
import gears.GearsBuilder;
import gears.LogLevel;
import gears.operations.AccumulateOperation;
import gears.operations.ForeachOperation;
import gears.operations.MapOperation;
import gears.operations.OnRegisteredOperation;
import gears.operations.OnUnregisteredOperation;
import gears.readers.StreamReader;
import gears.readers.StreamReader.FailurePolicy;

public class Connector implements ForeachOperation<ArrayList<HashMap<String,Object>>>,
AccumulateOperation<HashMap<String,Object>, ArrayList<HashMap<String, Object>>>,
OnRegisteredOperation,
OnUnregisteredOperation, Iterable<String>,
MapOperation<HashMap<String, Object>, HashMap<String, Object>>{
  
  public static final String ENTETY_NAME_STR = "__entityName__";
  public static final String EVENT_STR = "__event__";
  public static final String SOURCE_STR = "__source__";
  
  class RGHibernateStandardServiceInitiator implements StandardServiceInitiator<Service>{

    private Map values;
    
    public String getUrl() {
      return (String) values.get("hibernate.connection.url");
    }
    
    public String getUser() {
      return (String) values.get("hibernate.connection.username");
    }
    
    public String getDriverClass() {
      return (String) values.get("hibernate.connection.driver_class");
    }
    
    public String getDialect() {
      return (String) values.get("hibernate.dialect");
    }
    
    @Override
    public Class<Service> getServiceInitiated() {
      return null;
    }

    @Override
    public Service initiateService(Map configurationValues, ServiceRegistryImplementor registry) {
      values = configurationValues;
      return null;
    }
    
  }
  
  /**
   * 
   */
  private static final long serialVersionUID = 1L;

  public static final Map<String, Connector> connectors = new ConcurrentHashMap<>();
  
  static Collection<Connector> getAllConnectors() {
    return connectors.values();    
  }
  
  static Connector getConnector(String name) {
    return connectors.get(name);
  }
  
  private String name;
  private String uuid;
  private String xmlDef;
  private String url;
  private String driverClass;
  private String userName;
  private String dialect;
  private String registrationId;
  private String expectedStreamName;
  private transient RGHibernate connector;
  private int batchSize;
  private int duration;
  private int retryInterval;
  
  public transient ConcurrentLinkedDeque<WriteThroughMD> queue = null;
  
  public Connector() {}
  
  public Connector(String name, String xmlDef, int batchSize, int duration, int retryInterval) {
    this.name = name;
    this.xmlDef = xmlDef;
    
    System.setProperty("javax.xml.bind.JAXBContextFactory", "org.eclipse.persistence.jaxb.JAXBContextFactory");
    Thread.currentThread().setContextClassLoader(WriteBehind.class.getClassLoader());
    StandardServiceRegistryImpl tempRegistry = (StandardServiceRegistryImpl)new StandardServiceRegistryBuilder()
        .configure( InMemoryURLFactory.getInstance().build("configuration", this.xmlDef))
        .build();
    
    RGHibernateStandardServiceInitiator initiator = this.new RGHibernateStandardServiceInitiator();
    tempRegistry.initiateService(initiator);
    
    this.url = initiator.getUrl();
    this.driverClass = initiator.getDriverClass();
    this.userName = initiator.getUser();
    this.dialect = initiator.getDialect();
    this.batchSize = batchSize;
    this.duration = duration;
    this.retryInterval = retryInterval;
    
    tempRegistry.close();
    
    uuid = UUID.randomUUID().toString();
    String streamName = String.format("_Stream-%s-%s-*", name, uuid);
    StreamReader streamReader = new StreamReader()
        .setPattern(streamName)
        .setBatchSize(batchSize)
        .setDuration(duration)
        .setFailurePolicy(FailurePolicy.RETRY)
        .setFailureRertyInterval(retryInterval);

    GearsBuilder<HashMap<String,Object>> builder = GearsBuilder.CreateGearsBuilder(streamReader, String.format("%s connector", name));
    
    builder
    .map(this)
    .accumulate(this)
    .foreach(this)
    .map(ArrayList<HashMap<String, Object>>::size)
    .register(ExecutionMode.ASYNC_LOCAL, this, this);
    
    connector = RGHibernate.getOrCreate(name);
    connector.setXmlConf(xmlDef);
    connectors.put(this.name, this);
  }
  
  public String getUuid() {
    return uuid;
  }

  @Override
  public void onUnregistered() throws Exception {
    connectors.remove(this.name);
    connector.close();
  }
  
  public void addSource(Source s) {
    connector.addSource(s.getName(), s.getXmlDef());
  }
  
  public void removeSource(Source s) {
    connector.removeSource(s.getName());
  }

  @Override
  public void onRegistered(String registrationId) throws Exception {
    expectedStreamName = String.format("_Stream-%s-%s-{%s}", name, uuid, GearsBuilder.hashtag());
    queue = new ConcurrentLinkedDeque<>();
    this.registrationId = registrationId;
    connector = RGHibernate.getOrCreate(this.name);
    connector.setXmlConf(this.xmlDef);
    connectors.put(this.name, this);
    System.setProperty("javax.xml.bind.JAXBContextFactory", "org.eclipse.persistence.jaxb.JAXBContextFactory");
    Thread.currentThread().setContextClassLoader(WriteBehind.class.getClassLoader());
  }

  @Override
  public ArrayList<HashMap<String, Object>> accumulate(ArrayList<HashMap<String, Object>> a,
      HashMap<String, Object> r) throws Exception {
    if(a == null) {
      a = new ArrayList<>();
    }
    a.add(r);
    return a;
  }
  
  @Override
  public HashMap<String, Object> map(HashMap<String, Object> r) throws Exception {
    Map<String, byte[]> value = (Map<String, byte[]>) r.get("value");
    Map<String, String> map = value.entrySet().stream()
        .collect(Collectors.toMap(Map.Entry::getKey, e -> new String(e.getValue())));
    
    String sourceName = map.get(SOURCE_STR);
    
    Source source = (WriteSource)Source.getSource(sourceName);
    
    Map<String, Object> newMap = new HashMap<>();
    PropertyData idProperty =  source.getIdProperty();
    
    String val = map.remove(idProperty.getName());
    Object convertedVal = null;
    try {
      convertedVal = idProperty.convertToObject(val);
    }catch (Exception e) {
      String msg = String.format("Can not conver id property %s val %s, error='%s'", idProperty.getName(), val, e.toString());
      GearsBuilder.log(msg, LogLevel.WARNING);
      throw new Exception(msg);
    }

    newMap.put(idProperty.getName(), convertedVal);
    newMap.put(ENTETY_NAME_STR, map.remove(ENTETY_NAME_STR));
    newMap.put(EVENT_STR, map.remove(EVENT_STR));
    newMap.put(SOURCE_STR, map.remove(SOURCE_STR));
    
    for(String key : map.keySet()) {
      val = map.get(key);
      
      PropertyData pm = null;
      convertedVal = null;
      try {
        pm = source.getPropertyMapping(key);
        convertedVal = pm.convertToObject(val);
      }catch (Exception e) {
        String msg = String.format("Can not find property mapping for %s val %s, error='%s'", key, val, e.toString());
        GearsBuilder.log(msg, LogLevel.WARNING);
        throw new Exception(msg);
      }
      
      
      newMap.put(key, convertedVal);
    }
    
    r.put("value", newMap);
    
    return r;
  }

  @Override
  public void foreach(ArrayList<HashMap<String, Object>> record) throws Exception {
    String lastStreamId = null;
    String msg = null;
    Exception cause = null;
    synchronized (this.connector) {
      try {
        Session session = connector.getSession();
        Transaction transaction = session.beginTransaction();
        boolean isMerge = true;
        
        for(Map<String, Object> r: record) {
          lastStreamId = new String((byte[])r.get("id"));
          Map<String, Object> map = (Map<String, Object>) r.get("value");
          String sourceName = (String)map.remove(SOURCE_STR);
          
          String event = (String)map.remove(EVENT_STR);
          if(event.charAt(0) != 'd') {
            if(!isMerge) {
              transaction.commit();
              session.clear();
              transaction = session.beginTransaction();
              isMerge = true;
            }
            session.merge((String)map.remove(ENTETY_NAME_STR), map);
          }else {
            if(isMerge) {
              transaction.commit();
              session.clear();
              transaction = session.beginTransaction();
              isMerge = false;
            }
            Source source = Source.getSource(sourceName);
            Object o = session.get((String)map.remove(ENTETY_NAME_STR), (Serializable)map.get(source.getIdProperty().getName()));
            // o can be null on hdel that removed the last field
            if(o != null) {
              session.delete(o);
            }
          }
        }
        
        transaction.commit();
        session.clear();
      }catch (Exception e) {
        msg = String.format("Failed commiting transaction error='%s'", e.toString());
        GearsBuilder.log(msg, LogLevel.WARNING);
        connector.closeSession();
        lastStreamId = null;
        cause = e;
      }
      
      while(!queue.isEmpty()) {
        WriteThroughMD wtMD = queue.peek();
        
        if(wtMD.tryFree(lastStreamId)) {
          queue.remove();
          continue;
        }
        break;
      }
      
      if(msg != null) {
        throw new Exception(msg, cause);
      }
    }
    
  }
  
  public Object getObject(String entetyName, Serializable pk) throws Exception {
    Object o = null;
    synchronized (this.connector) {
      try {
        Session session = connector.getSession();
        session.clear();
        o = session.get(entetyName, pk);
      }catch(Exception e) {
        String msg = String.format("Failed fetching data from databse, error='%s'", e.toString());
        GearsBuilder.log(msg, LogLevel.WARNING);
        connector.closeSession();
        throw e;
      }
    }
    return o;
  }
  
  @Override
  public String toString() {
    return String.format("name: %s, xmlDef: %s", this.name, this.xmlDef);
  }

  @Override
  public Iterator<String> iterator() {
    List<String> s = new ArrayList<>(Arrays.asList("name", name, "url", url, "driverClass", driverClass,
        "userName", userName, "dialect", dialect, "uuid", uuid,
        "registrationId", registrationId, "batchSize", Integer.toString(batchSize),
        "duration", Integer.toString(duration), "retryInterval", Integer.toString(retryInterval),
        "streamName", expectedStreamName, "pendingClients", Integer.toString(queue.size())));
    Long backlog = (Long)GearsBuilder.execute("xlen", expectedStreamName);
    s.add("backlog");
    s.add(Long.toString(backlog));
    return s.iterator();
  }
  
  public void unregister() throws Exception {
    if(connector != null && connector.numSources() > 0) {
      throw new Exception("Can't unregister connector with sources");
    }
    GearsBuilder.execute("RG.UNREGISTER", registrationId);
  }

  public String getName() {
    return name;
  }

  public String getXmlDef() {
    return xmlDef;
  }

  public String getUrl() {
    return url;
  }

  public String getDriverClass() {
    return driverClass;
  }

  public String getUserName() {
    return userName;
  }

  public String getDialect() {
    return dialect;
  }

  public String getRegistrationId() {
    return registrationId;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public int getDuration() {
    return duration;
  }

  public int getRetryInterval() {
    return retryInterval;
  }

  public void setName(String name) {
    this.name = name;
  }

  public void setUuid(String uuid) {
    this.uuid = uuid;
  }

  public void setXmlDef(String xmlDef) {
    this.xmlDef = xmlDef;
  }

  public void setUrl(String url) {
    this.url = url;
  }

  public void setDriverClass(String driverClass) {
    this.driverClass = driverClass;
  }

  public void setUserName(String userName) {
    this.userName = userName;
  }

  public void setDialect(String dialect) {
    this.dialect = dialect;
  }

  public void setRegistrationId(String registrationId) {
    this.registrationId = registrationId;
  }

  public void setBatchSize(int batchSize) {
    this.batchSize = batchSize;
  }

  public void setDuration(int duration) {
    this.duration = duration;
  }

  public void setRetryInterval(int retryInterval) {
    this.retryInterval = retryInterval;
  }
  
  
}
