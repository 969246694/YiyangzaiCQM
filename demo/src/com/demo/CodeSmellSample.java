package com.demo;

import java.io.FileInputStream;
import java.io.InputStream;

/** 刻意编写的坏味道样本，用于验证静态规则检测能力（非业务代码）。 */
public class CodeSmellSample {

    /** 圈复杂度 > 15：多重嵌套分支。 */
    public int highComplexity(int a, int b, int c) {
        int r = 0;
        if (a > 0) {
            if (b > 0) {
                if (c > 0) {
                    r += 1;
                } else if (c < -10) {
                    r += 2;
                } else {
                    r -= 1;
                }
            } else if (b < -10) {
                if (c > 5) {
                    r += 3;
                } else if (c > 0) {
                    r += 4;
                } else {
                    r += 5;
                }
            } else {
                r += 6;
            }
        } else if (a < -10) {
            if (b > 10) {
                r += 7;
            } else if (b > 0) {
                r += 8;
            } else {
                r += 9;
            }
        } else {
            r += 10;
        }
        for (int i = 0; i < 3; i++) {
            if (i % 2 == 0) {
                r += i;
            }
        }
        int k = 0;
        while (k < 5) {
            if (k > 2 && r > 0) {
                r -= k;
            } else if (k == 1 || r < 0) {
                r += k;
            }
            k++;
        }
        r += (r > 100) ? 100 : (r < -100 ? -100 : r);
        switch (a) {
            case 1:
                r += 1;
                break;
            case 2:
                r += 2;
                break;
            case 3:
                r += 3;
                break;
            default:
                break;
        }
        return r;
    }

    /** 空 catch：异常被静默吞掉。 */
    public void emptyCatch() {
        try {
            Integer.parseInt("not-a-number");
        } catch (NumberFormatException e) {
        }
    }

    /** 资源未关闭：打开流但未关闭、也未使用 try-with-resources。 */
    public void unclosedResource(String path) throws Exception {
        InputStream in = new FileInputStream(path);
        int b = in.read();
        if (b < 0) {
            throw new IllegalStateException("empty");
        }
    }
}
