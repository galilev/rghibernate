package com.redislabs;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;

public class RGHibernate {

  private static MetadataSources sources;
  private static SessionFactory sessionFactory;

  public RGHibernate(String configuration) {
      StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
          .configure( InMemoryURLFactory.getInstance().build("configuration", configuration))
          .build();
      sources = new MetadataSources(registry);
  }


  public void addMapping(String mapping) {
    sources.addURL(InMemoryURLFactory.getInstance().build("mapping", mapping));    
    Metadata metadata = sources.getMetadataBuilder().build();
    sessionFactory = metadata.getSessionFactoryBuilder().build();
  }

  public Session openSession() {
    return sessionFactory.openSession();
  }
}

