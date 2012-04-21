/*
 * Copyright 2002-2012 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.springframework.integration.dsl.groovy.builder.dom

import groovy.xml.StreamingMarkupBuilder
import groovy.xml.XmlUtil
import org.springframework.beans.factory.config.BeanDefinitionHolder
import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.beans.factory.support.BeanDefinitionReaderUtils
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.apache.commons.logging.LogFactory
import org.apache.commons.logging.Log

import org.springframework.integration.dsl.groovy.*

/**
 * @author David Turanski
 * Translates Spring Integration DSL model to Spring XML
 */
class IntegrationMarkupSupport {
	private Log logger = LogFactory.getLog(IntegrationMarkupSupport)
	private IntegrationNamespaceSupport namespaceSupport = new IntegrationNamespaceSupport()
	/**
	 * Translate DSL context to XML	
	 * @param integrationContext
	 * @return Spring XML bean definitions
	 */
	def translateToXML(integrationContext) {

		resolveXMLBeanNamespaces(integrationContext.components.findAll {it instanceof XMLNamespace} )

		integrationContext.messageFlows?.each {messageFlow -> resolveMessageFlowChannels(messageFlow) }

		def xmlBuilder = new StreamingMarkupBuilder()
		def writer = xmlBuilder.bind { builder ->
			namespaces <<  namespaceSupport.namespaceDeclarations
			println namespaceSupport.namespaceDeclarations
			beans(namespaceSupport.schemaLocationDeclaration() ) {
				integrationContext.components.each {component ->
					if (component instanceof MessageFlow) {
						if (component.outputChannel) {
							createChannel(builder, component.outputChannel)
						}
						buildMessageFlow(builder, integrationContext.applicationContext, component)
					}

					else if (component instanceof SimpleEndpoint) {
						createChannel(builder, component.inputChannel)

						if (component.outputChannel) {
							createChannel(builder,component.outputChannel)
						}
						createEndpoint(builder, integrationContext.applicationContext,component)
					}

					else if (component instanceof RouterComposition) {
						buildRouter(builder, integrationContext.applicationContext, component)
					}
					else if (component instanceof XMLBean){
						Closure c = component.defn.dehydrate()
						c.delegate = builder
						c.call()
					}
				}
			}
		}

		def xml =  XmlUtil.serialize(writer)

		if (logger.isDebugEnabled()) {
			logger.debug(xml)
		}
		xml
	}

	private createEndpoint(builder, applicationContext, endpoint, Closure closure = null) {
		def siPrefix = namespaceSupport.integrationNamespacePrefix
		def name = endpoint.name
		assert endpoint.name, 'name cannot be null'

		if (closure) {
			closure.delegate = builder
		}

		def attributes = buildAttributes(endpoint)
		if (endpoint.hasProperty('action')) {
			assert !(attributes.containsKey('ref')), 'endoint cannot provide a bean reference and a closure'
			attributes.method='processMessage'
			def beanName = "${name}#handler"
			attributes.ref = beanName


			BeanDefinitionBuilder  handlerBuilder =
					BeanDefinitionBuilder.genericBeanDefinition(ClosureInvokingMessageProcessor)
			handlerBuilder.addConstructorArgValue(endpoint.action)
			def bdh = new BeanDefinitionHolder(handlerBuilder.getBeanDefinition(),beanName)
			BeanDefinitionReaderUtils.registerBeanDefinition(bdh, (BeanDefinitionRegistry) applicationContext)
		}
		if (endpoint.class == Transformer) {
			builder."$siPrefix:transformer"(attributes)
		}
		else if (endpoint.class == Filter) {
			builder."$siPrefix:filter"(attributes)
		}
		else if (endpoint.class == ServiceActivator) {
			builder."$siPrefix:service-activator"(attributes)
		}
		else if (endpoint.class == Bridge) {
			builder."$siPrefix:bridge"(attributes)
		}
		else if (endpoint.class == RouterComposition) {
			builder."$siPrefix:router"(attributes) {
				if (closure)closure.call()
			}
		}
	}

	private  buildAttributes(endpoint) {
		def attributes = endpoint.componentProperties

		if (endpoint.hasProperty('inputChannel')) {
			attributes.'input-channel' = endpoint.inputChannel
		}
		if (endpoint.hasProperty('outputChannel') && endpoint.outputChannel) {
			attributes.'output-channel' = endpoint.outputChannel
		}

		if (endpoint.hasProperty('ref')) {
			attributes.ref = endpoint.ref
		}

		if (endpoint.hasProperty('method')) {
			attributes.method = endpoint.method
		}

		attributes.id = endpoint.name

		attributes
	}

	private buildMessageFlow(builder, applicationContext, messageFlow) {
		def previousComponent = null
		messageFlow.components.each {component ->
			if (component instanceof FlowExecution ) {
				if (!previousComponent) {
					createBridge(builder,messageFlow.inputChannel, component.inputChannel)
				}
			}
			else if (component instanceof SimpleEndpoint) {
				createEndpoint(builder,applicationContext, component)
			}
			else if (component instanceof RouterComposition) {
				buildRouter(builder,applicationContext, component)
			}
			else if (component instanceof MessageFlow) {
				resolveMessageFlowChannels(component)
				buildMessageFlow(builder, applicationContext, component)
			}

			previousComponent = component
		}
	}

	protected resolveMessageFlowChannels(messageFlow) {

		def first = messageFlow.components.first()
		first.inputChannel = first.inputChannel ?: messageFlow.inputChannel

		def last = messageFlow.components.last()

		if (last instanceof MessageProducingEndpoint){
			last.outputChannel = last.outputChannel ?: messageFlow.outputChannel
		}

		def outputChannel

		messageFlow.components.eachWithIndex {component, i->

			if (component instanceof SimpleEndpoint ) {
				component.inputChannel = component.inputChannel ?: outputChannel


				if (component instanceof MessageProducingEndpoint ) {
					if (component != last && component.linkToNext) {
						component.outputChannel = outputChannel = component.outputChannel ?: channelName(component,messageFlow.components[i+1])
						//If component is Flow execution in the midst of a messageFlow, it requires an outputChannel
						if (component instanceof FlowExecution ) {
							def c = component.messageFlow.components.last()
							component.messageFlow.components.last().outputChannel = outputChannel
						}
					}

				}
			}
			else if (component instanceof RouterComposition ){

			}
			if (logger.isDebugEnabled()){
				logger.debug("resolved channels for SI component $component with inputChannel ${component.inputChannel}" +
						(component instanceof MessageProducingEndpoint ? " outputChannel ${component.outputChannel}": ""))
			}
		}
	}

	private buildRouter(builder, applicationContext, RouterComposition routerComposition) {
		def siPrefix = namespaceSupport.integrationNamespacePrefix
		if (logger.isDebugEnabled()){
			logger.debug("Building router composition")
		}

		def otherwise = routerComposition.components.find{it instanceof OtherwiseCondition}
		if (otherwise){
			routerComposition."default-output-channel" =
					otherwise.components.first().inputChannel  = "${otherwise.name}.inputChannel"
		}
		createEndpoint(builder, applicationContext, routerComposition) {
			routerComposition.channelMap?.each {value,channel ->
				"$siPrefix:mapping"(value:value, channel:channel)
			}

			routerComposition.components.each {component ->
				if (logger.isDebugEnabled()){
					logger.debug("building component $component")
				}

				if (component instanceof WhenCondition) {
					"$siPrefix:mapping"(value:component.value, channel:component.components.first().inputChannel)
				}
			}
		}

		routerComposition.components.findAll{it instanceof RouterCondition}.each {component ->
			buildMessageFlow(builder,applicationContext,component)
		}
	}

	private resolveXMLBeanNamespaces(xmlNamespaces) {
		xmlNamespaces?.each { namespace->
			namespace.name.split(',').each { ns->
				if (logger.isDebugEnabled()) {
					logger.debug("adding namespace for $ns")
				}
				namespaceSupport.addDefaultNamespace(ns)
			}
		}
	}

	private createBridge(builder,inputChannel,outputChannel){
		def siPrefix = namespaceSupport.integrationNamespacePrefix
		builder."$siPrefix:bridge"('input-channel':inputChannel,'output-channel':outputChannel)
	}

	private createChannel(builder, channelName) {
		def siPrefix = namespaceSupport.integrationNamespacePrefix
		builder."$siPrefix:channel"(id:channelName)
	}

	private String channelName(from,to){
		to.inputChannel? to.inputChannel: "from.${from.name}.to.${to.name}"
	}
}