/*
 * Copyright 2016 ITEA 12004 SEAS Project.
 *
 * Licensed under the Apache License, OntologyVersion 2.0 (the "License");
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
package com.github.thesmartenergy.seas;

import com.github.thesmartenergy.seas.entities.OntologyVersion;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.servlet.ServletContext;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

/**
 *
 * @author maxime.lefrancois
 */
@ApplicationScoped
public class App {

    private static final Logger LOG = Logger.getLogger(App.class.getSimpleName());

    private final String base = "https://w3id.org/seas/"; 

    
    /**
     * maps ontology names to a description of the list of versions (major+minor+file)
     */
    private final Map<String, TreeSet<OntologyVersion>> ontologyVersions = new HashMap<>();
    /**
     * maps resource names to ontology name where it is defined
     */
    private final Map<String, String> definingOntologies = new HashMap<>();

    @Inject
    ServletContext context;

    @PostConstruct
    public void initialize() {
        try {
            String dir = context.getRealPath("/WEB-INF/classes/");
            LOG.info(dir); 
            LOG.info(context.getRealPath("/WEB-INF/classes"));
            LOG.info(context.getClassLoader().getResource("/").toString());
            LOG.info(context.getClassLoader().getResource("/").getPath());
            LOG.info(context.getClassLoader().getResource("/").toURI().toString());

            File ontoDir = new File(dir + "/ontology/");
            Pattern p = Pattern.compile("^([a-zA-Z]*)-([0-9]+)\\.([0-9]+)\\.ttl$");

            Set<String> referencedResources = new HashSet<>();
            for (File ontoFile : ontoDir.listFiles()) {
                String ontoFileName = ontoFile.getName();
                System.out.println("testing "+ontoFileName); 

                // any file whose name does not conform to NAME-MAJOR.MINOR.ttl triggers a warning
                Matcher m = p.matcher(ontoFileName);
                boolean b = m.matches(); 
                // if matched
                if (!b) {
                    LOG.warning("File " + ontoFileName + " does not match the pattern NAME-MAJOR.MINOR.ttl");
                    continue;
                }
                String ontoName = m.group(1);
                int major = Integer.parseInt(m.group(2)); 
                int minor = Integer.parseInt(m.group(3));
                
                OntologyVersion ov = new OntologyVersion(major, minor, ontoFile);

                try {
                    Model ontology = ModelFactory.createDefaultModel().read(new FileReader(ontoFile), base, "TURTLE");                
                    checkOntologyDefinition(ontoName, major, minor, ontology);
                    Set<String> definedResources = extractDefinedResources(ontology, ontoName);
                    
                    if(!ontologyVersions.containsKey(ontoName)) {
                        ontologyVersions.put(ontoName, new TreeSet<OntologyVersion>());
                    }
                    ontologyVersions.get(ontoName).add(ov);
                    for(String resource : definedResources) {
                        String name = definingOntologies.get(resource);
                        if(!ontoName.equals(name)) {
                            LOG.log(Level.WARNING, "error: resource with URI <" + resource + "> is already defined in ontology  " + name);
                        }
                        definingOntologies.put(resource, ontoName);
                    }
                    referencedResources.addAll(extractReferencedResources(ontology, ontoName));
                    System.out.println("ok ontology version " + ontoName + " " + major + " " + minor);
                } catch (Exception e) {
                    LOG.warning("Error while parsing file " + ontoFileName + ": " + e.getMessage());
                    continue; 
                }
            }
            // checking every referenced resources is defined
            for(String uri : referencedResources) {
                if(!definingOntologies.containsKey(uri)) {
                    LOG.log(Level.WARNING, "resource with URI <" + uri + "> is never defined.");
                }
            }
        } catch (Exception ex) {
            LOG.log(Level.SEVERE, "error while initializing app ", ex);
            throw new RuntimeException("error while initializing app ", ex);
        }
        checkDependencies();
    }

    public String ontologyForResource(String resource) {
        return definingOntologies.get(resource);
    }
    
    public OntologyVersion getVersion(String ontoName) {
        if(!ontologyVersions.containsKey(ontoName)) {
            return null;
        }
        TreeSet<OntologyVersion> versions = ontologyVersions.get(ontoName);
        return versions.last();
    }
    
    public OntologyVersion getVersion(String ontoName, int major, int minor) {
        if(!ontologyVersions.containsKey(ontoName)) {
            return null;
        }
        for(OntologyVersion version : ontologyVersions.get(ontoName)) {
            if(version.getMajor() == major && version.getMinor() == minor) {
                return version;
            }
        }
        return null;
    }
    
//    public InputStream getAsStream(String ontoName, int major, int minor) throws Exception {
//        for(OntologyVersion version : ontologyVersions.get(ontoName)) {
//            if(version.major == major && version.minor == minor) {
//                return new FileInputStream(version.file);
//            }
//        }
//        throw new Exception("No file for ontology " + ontoName + " with version " + major + "." + minor);
//    }
    

    public String getBase() {
        return base;
    }

    private void checkOntologyDefinition(String ontoName, int ontoMajor, int ontoMinor, Model ontology) throws Exception {
        String ontoFileName = ontoName + "-" + ontoMajor + "." + ontoMinor;
        String expectedOntoURI = base + ontoName;
        String expectedOntoVersionURI = expectedOntoURI + "/" + ontoMajor + "." + ontoMinor;
        if(ontoName.equals("seas")) {
            expectedOntoURI = base;
            expectedOntoVersionURI = expectedOntoURI + ontoMajor + "." + ontoMinor;
        }
        String expectedOntoVersionInfo = "v" + ontoMajor + "." + ontoMinor;
        List<Resource> ontologyResources = ontology.listResourcesWithProperty(RDF.type, OWL2.Ontology).toList();
        if (ontologyResources.size() != 1) {
            throw new Exception("Must define exactly one ontology. got " + ontologyResources.size());
        }
        Resource ontologyResource = ontologyResources.get(0);
        if (!ontologyResource.getURI().equals(expectedOntoURI)) {
            throw new Exception("Must define ontology <" + expectedOntoURI + ">. Instead, got <" + ontologyResource.getURI());
        }
        checkOntologyVersionIRI(ontology, ontologyResource, expectedOntoVersionURI);
        checkOntologyVersionInfo(ontology, ontologyResource, expectedOntoVersionInfo);
    }
    

    private void checkOntologyVersionIRI(Model ontology, Resource ontologyResource, String expectedOntoVersionURI) throws Exception {
        List<RDFNode> versionIRIs = ontology.listObjectsOfProperty(ontologyResource, OWL2.versionIRI).toList();
        if (versionIRIs.size() != 1) {
            throw new Exception("Ontology " + ontologyResource.getURI() + " must define exactly one version IRI. Got " + versionIRIs.size());
        }
        RDFNode versionIRI = versionIRIs.get(0);
        if (!(versionIRI instanceof Resource)) {
            throw new Exception("Ontology " + ontologyResource.getURI() + " must have version IRI " + expectedOntoVersionURI + " got a " + versionIRI.getClass());
        }
        if (!((Resource) versionIRI).getURI().equals(expectedOntoVersionURI)) {
            throw new Exception("Ontology " + ontologyResource.getURI() + " must have version IRI " + expectedOntoVersionURI + ". got " + ((Resource) versionIRI).getURI());
        }
    }

    private void checkOntologyVersionInfo(Model ontology, Resource ontologyResource, String expectedOntoVersionInfo) throws Exception {
        List<RDFNode> versionInfos = ontology.listObjectsOfProperty(ontologyResource, OWL2.versionInfo).toList();
        if (versionInfos.size() != 1) {
            throw new Exception("Ontology " + ontologyResource.getURI() + " must define exactly one version Info. Got " + versionInfos.size());
        }
        RDFNode versionInfo = versionInfos.get(0);
        if (!(versionInfo instanceof Literal)) {
            throw new Exception("Ontology " + ontologyResource.getURI() + " must have version IRI " + expectedOntoVersionInfo + " got a " + versionInfo.getClass());
        }
        if (!((Literal) versionInfo).getLexicalForm().equals(expectedOntoVersionInfo)) {
            throw new Exception("Ontology " + ontologyResource.getURI() + " must have version IRI " + expectedOntoVersionInfo + ". got " + ((Literal) versionInfo).getLexicalForm());
        }
    }
    
    public Set<String> extractDefinedResources(Model ontology, String ontoName) throws Exception {
        Set<String> definedResources = new HashSet<String>();
        List<Statement> triples = ontology.listStatements(null, RDFS.isDefinedBy, (RDFNode) null).toList();
        LOG.info("Number of defined resources: " + triples.size());
        for(Statement s : triples) {
            if(!s.getSubject().getURI().startsWith(base)) {
                LOG.warning("IRI of defined resource <" + s.getSubject().getURI() + "> should start with <" + base + ">");
            }
            if(!(s.getObject() instanceof Resource) || !((Resource) s.getObject()).getURI().equals(base + ontoName) )  {
                LOG.warning("Resource <" + s.getSubject().getURI() + "> must be defined by <" + base + ontoName + ">. Got " + ((Resource) s.getObject()).getURI());
            }
            definedResources.add(s.getSubject().getURI());
        }
        return definedResources;
    }
    
    public Set<String> extractReferencedResources(Model ontology, String ontoName) throws Exception {
        final Set<String> referencedResources = new HashSet<String>();
        ontology.listStatements().forEachRemaining(new Consumer<Statement>() {
            @Override
            public void accept(Statement t) {
                addReferencedResource(referencedResources, t.getSubject());
                addReferencedResource(referencedResources, t.getPredicate());
                addReferencedResource(referencedResources, t.getObject());
            }
        });
        return referencedResources;
    }
    
    public void addReferencedResource(Set<String> referencedResources, RDFNode n) {
        if(n.isURIResource()) {
            String uri = n.asResource().getURI();
            if(uri.startsWith(base)) {
                referencedResources.add(uri);            
            }
        }
    }
    
    /**
     * check dependencies between ontologies: if an ontology uses a term of another, then it should import the other.
     */
    private void checkDependencies() {
        
    }
}
