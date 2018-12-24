/*
 * Copyright 2018 Artem Labazin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package feign.form;

import static feign.form.ContentType.MULTIPART;
import static lombok.AccessLevel.PRIVATE;

import feign.Request;
import feign.RequestTemplate;
import feign.codec.Encoder;
import feign.form.multipart.ByteArrayWriter;
import feign.form.multipart.DelegateWriter;
import feign.form.multipart.FormDataWriter;
import feign.form.multipart.ManyFilesWriter;
import feign.form.multipart.Output;
import feign.form.multipart.ParameterWriter;
import feign.form.multipart.PojoWriter;
import feign.form.multipart.SingleFileWriter;
import feign.form.multipart.Writer;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import lombok.experimental.FieldDefaults;
import lombok.val;

/**
 * @author Artem Labazin
 */
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class MultipartFormContentProcessor implements ContentProcessor {

  LinkedList<Writer> writers;

  Writer defaultPerocessor;

  /**
   * Constructor with specific delegate encoder.
   *
   * @param delegate specific delegate encoder for cases, when this processor couldn't handle
   *     request parameter.
   */
  public MultipartFormContentProcessor(Encoder delegate) {
    writers = new LinkedList<Writer>();
    addWriter(new ByteArrayWriter());
    addWriter(new FormDataWriter());
    addWriter(new SingleFileWriter());
    addWriter(new ManyFilesWriter());
    addWriter(new ParameterWriter());
    addWriter(new PojoWriter(writers));

    defaultPerocessor = new DelegateWriter(delegate);
  }

  @Override
  public void process(RequestTemplate template, Charset charset, Map<String, Object> data)
      throws Exception {
    val boundary = Long.toHexString(System.currentTimeMillis());
    val output = new Output(charset);

    for (val entry : data.entrySet()) {
      val writer = findApplicableWriter(entry.getValue());
      writer.write(output, boundary, entry.getKey(), entry.getValue());
    }

    output.write("--").write(boundary).write("--").write(CRLF);

    val contentTypeHeaderValue =
        new StringBuilder()
            .append(getSupportedContentType().getHeader())
            .append("; charset=")
            .append(charset.name())
            .append("; boundary=")
            .append(boundary)
            .toString();

    template.header(CONTENT_TYPE_HEADER, new String[0]); // reset header
    template.header(CONTENT_TYPE_HEADER, contentTypeHeaderValue);

    // Feign's clients try to determine binary/string content by charset presence
    // so, I set it to null (in spite of availability charset) for backward compatibility.
    val bytes = output.toByteArray();
    val body = Request.Body.encoded(bytes, null);
    template.body(body);

    output.close();
  }

  @Override
  public ContentType getSupportedContentType() {
    return MULTIPART;
  }

  /**
   * Adds {@link Writer} instance in runtime.
   *
   * @param writer additional writer.
   */
  public final void addWriter(Writer writer) {
    writers.add(writer);
  }

  /**
   * Adds {@link Writer} instance in runtime at the beginning of writers list.
   *
   * @param writer additional writer.
   */
  public final void addFirstWriter(Writer writer) {
    writers.addFirst(writer);
  }

  /**
   * Adds {@link Writer} instance in runtime at the end of writers list.
   *
   * @param writer additional writer.
   */
  public final void addLastWriter(Writer writer) {
    writers.addLast(writer);
  }

  /**
   * Returns the <b>unmodifiable</b> list of all writers.
   *
   * @return writers list.
   */
  public final List<Writer> getWriters() {
    return Collections.unmodifiableList(writers);
  }

  /**
   * Replaces the writer at the specified position with new element.
   *
   * @param index index of the element for replace.
   * @param writer writer to be stored at specified position.
   */
  public final void setWriter(int index, Writer writer) {
    writers.set(index, writer);
  }

  private Writer findApplicableWriter(Object value) {
    for (val writer : writers) {
      if (writer.isApplicable(value)) {
        return writer;
      }
    }
    return defaultPerocessor;
  }
}
