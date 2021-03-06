/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka

import se.scalablesolutions.akka.config.ConfiguratorRepository
import se.scalablesolutions.akka.rest.ActorComponentProviderFactory
import se.scalablesolutions.akka.util.Logging

import com.sun.jersey.api.core.ResourceConfig
import com.sun.jersey.spi.container.servlet.ServletContainer
import com.sun.jersey.spi.container.WebApplication

import javax.servlet.{ServletConfig}
import javax.servlet.http.{HttpServletRequest, HttpServletResponse}

import org.atmosphere.cpr.{AtmosphereServlet, AtmosphereServletProcessor, AtmosphereResource, AtmosphereResourceEvent,CometSupport}
import org.atmosphere.handler.{ReflectorServletProcessor, AbstractReflectorAtmosphereHandler}
import org.atmosphere.core.JerseyBroadcaster

/**
 * Akka's servlet to be used when deploying actors exposed as REST services in a standard servlet container,
 * e.g. not using the Akka Kernel.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class AkkaServlet extends ServletContainer {
  import org.scala_tools.javautils.Imports._

  override def initiate(resourceConfig: ResourceConfig, webApplication: WebApplication) = {
    Kernel.boot // will boot if not already booted by 'main'

    val configurators = ConfiguratorRepository.getConfigurators

    resourceConfig.getClasses.addAll(configurators.flatMap(_.getComponentInterfaces).asJava)
    resourceConfig.getProperties.put(
      "com.sun.jersey.spi.container.ResourceFilters",
      Config.config.getList("akka.rest.filters").mkString(","))

    webApplication.initiate(resourceConfig, new ActorComponentProviderFactory(configurators))
  }
}

/**
 * Akka's Comet servlet to be used when deploying actors exposed as Comet (and REST) services in a
 * standard servlet container, e.g. not using the Akka Kernel.
 * <p/>
 * Used by the Akka Kernel to bootstrap REST and Comet.
 */
class AkkaCometServlet extends org.atmosphere.cpr.AtmosphereServlet with Logging {
  val servlet = new AkkaServlet with AtmosphereServletProcessor {

    //Delegate to implement the behavior for AtmosphereHandler
    private val handler = new AbstractReflectorAtmosphereHandler {
      override def onRequest(event: AtmosphereResource[HttpServletRequest, HttpServletResponse]) {
        if (event ne null) {
          event.getRequest.setAttribute(ReflectorServletProcessor.ATMOSPHERE_RESOURCE, event)
          event.getRequest.setAttribute(ReflectorServletProcessor.ATMOSPHERE_HANDLER, this)
          service(event.getRequest, event.getResponse)
        }
      }
    }

    override def onStateChange(event: AtmosphereResourceEvent[HttpServletRequest, HttpServletResponse]) {
      if (event ne null) handler onStateChange event
    }

    override def onRequest(resource: AtmosphereResource[HttpServletRequest, HttpServletResponse]) {
      handler onRequest resource
    }
  }

  override def loadConfiguration(sc: ServletConfig) {
    atmosphereHandlers.put("/*", new AtmosphereServlet.AtmosphereHandlerWrapper(servlet, new JerseyBroadcaster))

    loadCometSupport(sc.getInitParameter("cometSupport")) map( setCometSupport(_) )
  }

  private def loadCometSupport(fqn : String) = {

      log.info("Trying to load: " + fqn)
      try {
        Some(Class.forName(fqn)
                  .getConstructor(Array(classOf[AtmosphereConfig]): _*)
                  .newInstance(config)
                  .asInstanceOf[CometSupport[_ <: AtmosphereResource[_,_]]])
      } catch {
          case e : Exception =>
              log.error(e, "Couldn't load comet support", fqn)
              None
      }
  }
}
