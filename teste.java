# opção A - lista YAML
hazelcast:
  port: 5701
  members:
    - servidor-a.qab.interno
    - servidor-b.qab.interno

# opção B - string separada por vírgula
hazelcast:
  port: 5701
  members: "servidor-a.qab.interno,servidor-b.qab.interno"
