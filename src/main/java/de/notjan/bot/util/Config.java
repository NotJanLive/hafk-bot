package de.notjan.bot.util;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.util.Map;

public class Config {
    private Map<String, Object> config;
    private File configFile;
    private Yaml yaml;

    public Config(String path) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        yaml = new Yaml(options);

        configFile = new File(path);

        try {
            if (configFile.exists()) {
                InputStream inputStream = new FileInputStream(configFile);
                config = yaml.load(inputStream);
            } else {
                throw new FileNotFoundException("Config file not found: " + path);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public Object get(ConfigKey key) {
        return config.get(key.toString());
    }

    public void set(ConfigKey key, Object value) {
        config.put(key.toString(), value);
        try {
            yaml.dump(config, new FileWriter(configFile));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}