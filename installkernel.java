///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.6.3
//DEPS com.fasterxml.jackson.core:jackson-databind:2.12.3


import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import static java.lang.String.format;
import static java.lang.System.out;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.SerializationFeature;

@Command(name = "install-kernel", mixinStandardHelpOptions = true, version = "install-kernel 0.1",
        description = "Installs JVM based Kernels that can be run via maven artifacts using JBang", showDefaultValues=true)
class installkernel implements Callable<Integer> {

    private static final String CONNECTION_FILE_MARKER = "{connection_file}";
    private static final String LANGUAGE = "java";
    private static final String INTERRUPT_MODE = "message";

    private enum Kernels {
                IJAVA { 
                    String shortName() { return "IJava"; }
                    String ga() { return "com.github.waikato.thirdparty:ijava"; } 
                    String v() { return "1.3.0"; }
                    String info() { return "https://github.com/SpencerPark/IJava";}
                },
                RAPAIO { 
                    String info() { return "https://github.com/padreati/rapaio-jupyter-kernel"; }
                    String shortName() { return "Rapaio"; }
                    String ga() { return "io.github.padreati:rapaio-jupyter-kernel"; } 
                    String v() { return "1.3.0"; }
                    String javaVersion() { return "21"; }
                    List<String> modules() { return List.of("java.base", "jdk.incubator.vector"); }
                    Map<String, String> options(String compilerOptions, long timeout) {
                        return Map.of(
                                    "RJK_COMPILER_OPTIONS",compilerOptions,
                                    "RJK_INIT_SCRIPT", "",
                                    "RJK_TIMEOUT_MILLIS", ""+timeout);
                    }
                },
                GANYMEDE { 
                    String info() { return "https://github.com/allen-ball/ganymede"; }
                    String shortName() { return "Ganymede"; }
                    String ga() { return "dev.hcf.ganymede:ganymede"; } 
                    String v() { return "2.1.2.20230910"; }
                    String javaVersion() { return "11"; }
                    List<String> arguments() { return List.of(
                            //from Ganymede Install.java
                        "-f", CONNECTION_FILE_MARKER); }

                  // List<String> modules() { return List.of("java.base", "jdk.incubator.vector"); }
                }
               /** needs classpath argument that is too tricky to do manually
                    See https://github.com/jbangdev/jbang/issues/1703  
                ,KOTLIN {
                    String shortName() { return "Kotlin"; }
                    String ga() { return "org.jetbrains.kotlinx:kotlin-jupyter-kernel-shadowed"; } 
                    String v() { return "0.12.0-85"; }
                    String javaVersion() { return "11"; }
                    String mainClass() { return "org.jetbrains.kotlinx.jupyter.IkotlinKt"; }
                    List<String> dependencies() {
                        return List.of("org.jetbrains.kotlinx:kotlin-jupyter-lib:0.12.0-85");
                    } 
                };
                    **/
                    ;
                

            String shortName() { return name().substring(0, 1).toUpperCase() + name().substring(1); }
            abstract String ga();
            String v() { return "RELEASE"; }
            String gav() { return ga() + ":" + v(); }
            String javaVersion() { return "11+"; }
            String mainClass() { return null; }
            String language() { return LANGUAGE;}
            List<String> dependencies() { return List.of(); }
            List<String> modules() { return List.of(); }
            List<String> jvmArguments() { return List.of(
                "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
                        "--illegal-access=permit"
                     //   "-Djava.awt.headless=true",
                     //   "-Djdk.disableLastUsageTracking=true"
            );}
            List<String> arguments() { return List.of(CONNECTION_FILE_MARKER); }
            Map<String, String> options(String compilerOptions, long timeout) {
                return Map.of();
            }
            String info() { return null; }
        }


        @Parameters(index = "0", defaultValue = "ijava", description = "The kernel to install. Possible values: ${COMPLETION-CANDIDATES}")
        Kernels kernel;

        @Option(names = "--verbose")
        boolean verbose;

        @Option(names = "--name")
        String name;

        String name() { 
            return name==null? kernel.shortName() + " - " + kernel.language() + " (JBang)":name;
        }

        @Option(names = "--jupyter-kernel-dir", description = "The name of directory to install the kernel to. Defaults to OS specific location.")
        String jupyterKernelDir;

        @Option(names = "--kernel-dir", description = "The name of directory to install the kernel to. Defaults to jbang-<kernel>")
        String kernelDir;

        String kernelDir() { 
            return kernelDir==null?"jbang-" + kernel.name().toLowerCase():kernelDir;
        }

        @Option(names="--timeout", defaultValue = "-1", description = "Timeout in milliseconds for kernel execution")
        long timeout;

        @Option(names="--compiler-options", defaultValue = "", description = "Compiler options to pass to the kernel")
        String compilerOptions;

        private enum OSName {
                LINUX,
                WINDOWS,
                MAC,
                SOLARIS
            }
        
            private OSName findOSName() {
                String os = System.getProperty("os.name").toLowerCase();
        
                if (os.contains("win")) {
                    return OSName.WINDOWS;
                } else if (os.contains("mac")) {
                    return OSName.MAC;
                } else if (os.contains("sunos")) {
                    return OSName.SOLARIS;
                } else if (os.contains("nix") || os.contains("nux") || os.contains("aix")) {
                    return OSName.LINUX;
                }
                return null;
            }
            
            private String getUserHome() {
                String home = System.getProperty("user.home");
                if (home != null && home.endsWith("/")) {
                    home = home.substring(0, home.length() - 1);
                }
                return home;
            }

        private List<String> getInstallationPaths(OSName os) {

        return switch (os) {
            case LINUX, SOLARIS -> List.of(
                    getUserHome() + "/.local/share/jupyter/kernels",
                    "/usr/local/share/jupyter/kernels",
                    "/usr/share/jupyter/kernels"
            );
            case MAC -> List.of(
                    getUserHome() + "/Library/Jupyter/kernels",
                    "/usr/local/share/jupyter/kernels",
                    "/usr/share/jupyter/kernels"
            );
            case WINDOWS -> List.of(
                    System.getenv("APPDATA") + "/jupyter/kernels",
                    System.getenv("PROGRAMDATA") + "/jupyter/kernels"
            );
        };
    }

    public static void main(String... args) {
        int exitCode = new CommandLine(new installkernel()).setCaseInsensitiveEnumValuesAllowed(true).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception { 
        
        OSName os = findOSName();
        if (os == null) {
            throw new RuntimeException("Operating system is not recognized. Installation failed.");
        }

        List<String> installationPath = null;
        if(jupyterKernelDir!=null) {
            installationPath = List.of(jupyterKernelDir);
        } else {
            installationPath = getInstallationPaths(os);
        }

        verbose("Considering " + String.join(",", installationPath));
        
        Path command = null;
        String[] paths = System.getenv("PATH").split(File.pathSeparator);
        for (String path : paths) {
            if (os.equals(OSName.WINDOWS)) {
                command = Path.of(path, "jbang.cmd");
                if (Files.exists(command) && Files.isExecutable(command)) {
                    break;
                } 
                command = Path.of(path, "jbang.ps1");
                if (Files.exists(command) && Files.isExecutable(command)) {
                    break;
                } 
            }
            command = Path.of(path, "jbang");
            if (Files.exists(command) && Files.isExecutable(command)) {
                break;
            }
            command = null;
        }
        if (command == null) {
            throw new IllegalStateException("jbang executable not found in PATH. Please ensure it is available before running javajupyter.");
        }
        var commandList = new ArrayList<String>(); 
        commandList.add(command.toAbsolutePath().toString());
        commandList.add("--java");
        commandList.add(kernel.javaVersion());
        
        if(kernel.mainClass()!=null) {
            commandList.add("-m");
            commandList.add(kernel.mainClass());
        }

        if(kernel.modules().size()>0) {
            commandList.add("-R--add-modules");
            commandList.add("-R" + String.join(",", kernel.modules()));
        }

        kernel.jvmArguments().forEach(jvmArg -> {
            commandList.add("-R" + jvmArg);
        });
        
        commandList.add(kernel.gav());
        
        
        commandList.addAll(kernel.arguments());

        KernelJson json = new KernelJson(commandList, 
                                name(), 
                                kernel.language(), 
                                INTERRUPT_MODE, 
                                kernel.options(compilerOptions, timeout));


        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        DefaultPrettyPrinter prettyPrinter = new DefaultPrettyPrinter();
        prettyPrinter.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);
        objectMapper.setDefaultPrettyPrinter(prettyPrinter);

        String jsonString = objectMapper.writeValueAsString(json);

        if(!Files.exists(Paths.get(installationPath.get(0)))) {
            throw new IllegalStateException("Jupyter Kernel path " + installationPath.get(0) + " does not exist. Please ensure it is available before running javajupyter.");
        }

        var output = Paths.get(installationPath.get(0), kernelDir(), "kernel.json");

        verbose(format("Writing: %s\nto %s", jsonString, output));
        try {
            if (!Files.exists(output.getParent())) {
                Files.createDirectories(output.getParent());
            }
            Files.write(output, jsonString.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            System.out.println(kernel.shortName() + " kernel installed to " + output);
            if(kernel.info()!=null) {
                System.out.println("For more information on this specific kernel: " + kernel.info());
            }
            System.out.println("\nBrought to you by https://github.com/jupyter-java");
       } catch (IOException e) {
            e.printStackTrace();
        }
        

        return 0;
    }

    void verbose(String msg) { 
        if(verbose) {
            out.println(msg);
        }
    }

    

public record KernelJson(
         List<String> argv,
        String displayName,
         String language,
         String interruptMode,
        Map<String, String> env) {
}
}
