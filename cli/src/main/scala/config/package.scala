package terraverse

package object config {
  type LoadedConfig    = LoadedConfigType[Config]
  type LoadedConfigRaw = LoadedConfigType[ConfigRaw]
}
