package com.jesper.seckill.exception;

import com.jesper.seckill.result.CodeMsg;

public class SecKillException extends RuntimeException{
    private CodeMsg codeMsg;

    public SecKillException(CodeMsg codeMsg) {
        super(codeMsg.toString());
        this.codeMsg = codeMsg;
    }

    public CodeMsg getCodeMsg() {
        return codeMsg;
    }
}
