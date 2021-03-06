/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.mapper;

import org.apache.lucene.document.Field;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermInSetQuery;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.lucene.Lucene;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.fielddata.IndexFieldData;
import org.elasticsearch.index.fielddata.plain.PagedBytesIndexFieldData;
import org.elasticsearch.index.query.QueryShardContext;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * A mapper for the _id field. It does nothing since _id is neither indexed nor
 * stored, but we need to keep it so that its FieldType can be used to generate
 * queries.
 */
public class IdFieldMapper extends MetadataFieldMapper {

    public static final String NAME = "_id";

    public static final String CONTENT_TYPE = "_id";

    public static class Defaults {
        public static final String NAME = IdFieldMapper.NAME;

        public static final MappedFieldType FIELD_TYPE = new IdFieldType();

        static {
            FIELD_TYPE.setTokenized(false);
            FIELD_TYPE.setIndexOptions(IndexOptions.DOCS);
            FIELD_TYPE.setStored(true);
            FIELD_TYPE.setOmitNorms(true);
            FIELD_TYPE.setIndexAnalyzer(Lucene.KEYWORD_ANALYZER);
            FIELD_TYPE.setSearchAnalyzer(Lucene.KEYWORD_ANALYZER);
            FIELD_TYPE.setName(NAME);
            FIELD_TYPE.freeze();
        }
    }

    public static class TypeParser implements MetadataFieldMapper.TypeParser {
        @Override
        public MetadataFieldMapper.Builder parse(String name, Map<String, Object> node, ParserContext parserContext) throws MapperParsingException {
            throw new MapperParsingException(NAME + " is not configurable");
        }

        @Override
        public MetadataFieldMapper getDefault(MappedFieldType fieldType, ParserContext context) {
            final IndexSettings indexSettings = context.mapperService().getIndexSettings();
            return new IdFieldMapper(indexSettings, fieldType);
        }
    }

    static final class IdFieldType extends TermBasedFieldType {

        IdFieldType() {
        }

        protected IdFieldType(IdFieldType ref) {
            super(ref);
        }

        @Override
        public MappedFieldType clone() {
            return new IdFieldType(this);
        }

        @Override
        public String typeName() {
            return CONTENT_TYPE;
        }

        @Override
        public boolean isSearchable() {
            // The _id field is always searchable.
            return true;
        }

        @Override
        public Query termQuery(Object value, @Nullable QueryShardContext context) {
            return termsQuery(Arrays.asList(value), context);
        }

        @Override
        public Query termsQuery(List<?> values, @Nullable QueryShardContext context) {
            if (indexOptions() != IndexOptions.NONE) {
                // 6.x index, _id is indexed
                return super.termsQuery(values, context);
            }
            // 5.x index, _uid is indexed
            return new TermInSetQuery(UidFieldMapper.NAME, Uid.createUidsForTypesAndIds(context.queryTypes(), values));
        }

        @Override
        public IndexFieldData.Builder fielddataBuilder() {
            if (indexOptions() == IndexOptions.NONE) {
                throw new IllegalArgumentException("Fielddata access on the _uid field is disallowed");
            }
            return new PagedBytesIndexFieldData.Builder(
                    TextFieldMapper.Defaults.FIELDDATA_MIN_FREQUENCY,
                    TextFieldMapper.Defaults.FIELDDATA_MAX_FREQUENCY,
                    TextFieldMapper.Defaults.FIELDDATA_MIN_SEGMENT_SIZE);
        }
    }

    static MappedFieldType defaultFieldType(IndexSettings indexSettings) {
        MappedFieldType defaultFieldType = Defaults.FIELD_TYPE.clone();
        if (indexSettings.isSingleType()) {
            defaultFieldType.setIndexOptions(IndexOptions.DOCS);
            defaultFieldType.setStored(true);
        } else {
            defaultFieldType.setIndexOptions(IndexOptions.NONE);
            defaultFieldType.setStored(false);
        }
        return defaultFieldType;
    }

    private IdFieldMapper(IndexSettings indexSettings, MappedFieldType existing) {
        this(existing == null ? defaultFieldType(indexSettings) : existing, indexSettings);
    }

    private IdFieldMapper(MappedFieldType fieldType, IndexSettings indexSettings) {
        super(NAME, fieldType, defaultFieldType(indexSettings), indexSettings.getSettings());
    }

    @Override
    public void preParse(ParseContext context) throws IOException {
        super.parse(context);
    }

    @Override
    public void postParse(ParseContext context) throws IOException {}

    @Override
    protected void parseCreateField(ParseContext context, List<IndexableField> fields) throws IOException {
        if (fieldType.indexOptions() != IndexOptions.NONE || fieldType.stored()) {
            Field id = new Field(NAME, context.sourceToParse().id(), fieldType);
            fields.add(id);
        }
    }

    @Override
    protected String contentType() {
        return CONTENT_TYPE;
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        return builder;
    }

    @Override
    protected void doMerge(Mapper mergeWith, boolean updateAllTypes) {
        // do nothing here, no merging, but also no exception
    }
}
