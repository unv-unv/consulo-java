/*
 * Copyright (c) 2007-2009, Osmorc Development Team
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright notice, this list
 *       of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright notice, this
 *       list of conditions and the following disclaimer in the documentation and/or other
 *       materials provided with the distribution.
 *     * Neither the name of 'Osmorc Development Team' nor the names of its contributors may be
 *       used to endorse or promote products derived from this software without specific
 *       prior written permission.
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL
 * THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT
 * OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR
 * TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE,
 * EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.osmorc.manifest.lang.psi.elementtype;

import consulo.language.ast.ASTNode;
import consulo.language.psi.stub.IndexSink;
import consulo.language.psi.stub.StubElement;
import consulo.language.psi.stub.StubInputStream;
import consulo.language.psi.stub.StubOutputStream;
import jakarta.annotation.Nonnull;
import org.osmorc.manifest.lang.psi.Header;
import org.osmorc.manifest.lang.psi.impl.HeaderImpl;
import org.osmorc.manifest.lang.psi.stub.HeaderStub;
import org.osmorc.manifest.lang.psi.stub.impl.HeaderStubImpl;

import java.io.IOException;

/**
 * @author Robert F. Beeger (robert@beeger.net)
 */
public class HeaderElementType extends AbstractManifestStubElementType<HeaderStub, Header> {
  public HeaderElementType() {
    super("HEADER");
  }


  @Override
  public Header createPsi(@Nonnull HeaderStub stub) {
    return new HeaderImpl(stub, this);
  }

  @Override
  public Header createPsi(ASTNode node) {
    return new HeaderImpl(node);
  }

  @Override
  public HeaderStub createStub(@Nonnull Header psi, StubElement parentStub) {
    return new HeaderStubImpl(parentStub, psi.getName());
  }

  public void serialize(@Nonnull HeaderStub stub, @Nonnull StubOutputStream dataStream) throws IOException {
    dataStream.writeName(stub.getName());
  }

  @Nonnull
  public HeaderStub deserialize(@Nonnull StubInputStream dataStream, StubElement parentStub) throws IOException {
    return new HeaderStubImpl(parentStub, dataStream.readName().toString());
  }

  public void indexStub(@Nonnull HeaderStub stub, @Nonnull IndexSink sink) {
  }
}
