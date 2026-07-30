package com.automationstudio.api.source.materialization;

public interface SourceMaterializer {

    SourceMaterializationResult materialize(SourceMaterializationRequest request);
}
