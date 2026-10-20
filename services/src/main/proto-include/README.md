# Import-only .proto files

Files here can be `import`ed by the .proto files in `src/main/proto/`, but no Java is generated
for them. Use it for files that only define custom options (annotations) and for
`google/protobuf/descriptor.proto`, which those option files import. Protobuf lite (Android) has
no Java classes for descriptor.proto, so generating it would fail; the options aren't needed at
runtime anyway.

`google/protobuf/descriptor.proto` is copied from protobuf-java 3.25.9 (matching protoc).
